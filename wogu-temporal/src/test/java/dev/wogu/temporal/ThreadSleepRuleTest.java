package dev.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wogu.api.CallPathFrame;
import dev.wogu.api.RuleResult;
import dev.wogu.api.ValidationContext;
import dev.wogu.api.ValidatorRunOutcome;
import dev.wogu.api.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration-style tests for WG002, exercised through {@link TemporalWorkflowValidator}. */
class ThreadSleepRuleTest {

  @TempDir Path sourceRoot;

  private final TemporalWorkflowValidator validator = new TemporalWorkflowValidator();

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private ValidationContext context() {
    return new TestValidationContext("test-project", sourceRoot, List.of(sourceRoot));
  }

  private RuleResult wg002Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG002"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void flagsThreadSleepDirectlyInsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;
        import io.temporal.workflow.WorkflowMethod;

        @WorkflowInterface
        public interface PaymentWorkflow {
          @WorkflowMethod
          void process();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() throws InterruptedException {
            Thread.sleep(5000);
          }
        }
        """);

    RuleResult wg002 = wg002Result();

    assertThat(wg002.passed()).isFalse();
    assertThat(wg002.violations()).hasSize(1);
    Violation violation = wg002.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG002");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("blocks the current worker thread");
    assertThat(violation.suggestedFix()).contains("Workflow.sleep(Duration)");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "Thread.sleep()");
  }

  @Test
  void flagsThreadSleepInsideAServiceCalledByTheWorkflow() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;
        import io.temporal.workflow.WorkflowMethod;

        @WorkflowInterface
        public interface PaymentWorkflow {
          @WorkflowMethod
          void process();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final PaymentService paymentService = new PaymentService();

          @Override
          public void process() {
            paymentService.waitForSettlement();
          }
        }
        """);
    writeJavaFile(
        "com/example/PaymentService.java",
        """
        package com.example;

        public class PaymentService {
          void waitForSettlement() {
            try {
              Thread.sleep(5000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        }
        """);

    RuleResult wg002 = wg002Result();

    assertThat(wg002.violations()).hasSize(1);
    Violation violation = wg002.violations().get(0);
    assertThat(violation.className()).isEqualTo("PaymentService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "PaymentService.waitForSettlement()", "Thread.sleep()");
  }

  @Test
  void doesNotFlagThreadSleepInsideAnActivityImplementationInvokedThroughItsInterface() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;
        import io.temporal.workflow.WorkflowMethod;

        @WorkflowInterface
        public interface PaymentWorkflow {
          @WorkflowMethod
          void process();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private PaymentActivity activity;

          @Override
          public void process() {
            activity.chargeCard();
          }
        }
        """);
    writeJavaFile(
        "com/example/PaymentActivity.java",
        """
        package com.example;

        public interface PaymentActivity {
          void chargeCard();
        }
        """);
    writeJavaFile(
        "com/example/PaymentActivityImpl.java",
        """
        package com.example;

        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public void chargeCard() {
            try {
              Thread.sleep(5000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        }
        """);

    RuleResult wg002 = wg002Result();

    assertThat(wg002.passed()).isTrue();
    assertThat(wg002.violations()).isEmpty();
  }

  @Test
  void stillFlagsThreadSleepInsideWorkflowSideEffect() throws IOException {
    // Unlike WG001/WG003-WG007, WG002 is deliberately not suppressed inside
    // Workflow.sideEffect(...): blocking the worker thread is unsafe there too, since
    // sideEffect still runs on the workflow's own thread, just once instead of on replay.
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;
        import io.temporal.workflow.WorkflowMethod;

        @WorkflowInterface
        public interface PaymentWorkflow {
          @WorkflowMethod
          void process();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(Integer.class, () -> {
              try {
                Thread.sleep(5000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return 1;
            });
          }
        }
        """);

    RuleResult wg002 = wg002Result();

    assertThat(wg002.passed()).isFalse();
    assertThat(wg002.violations()).hasSize(1);
    assertThat(wg002.violations().get(0).callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "Thread.sleep()");
  }
}
