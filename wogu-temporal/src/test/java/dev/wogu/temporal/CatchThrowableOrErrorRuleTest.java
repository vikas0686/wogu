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

/** Integration-style tests for WG013, exercised through {@link TemporalWorkflowValidator}. */
class CatchThrowableOrErrorRuleTest {

  @TempDir Path sourceRoot;

  private final TemporalWorkflowValidator validator = new TemporalWorkflowValidator();

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private void writeWorkflowInterface() throws IOException {
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
  }

  private ValidationContext context() {
    return new TestValidationContext("test-project", sourceRoot, List.of(sourceRoot));
  }

  private RuleResult wg013Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG013"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void flagsCatchingThrowableDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            try {
              riskyStep();
            } catch (Throwable t) {
              // swallowed
            }
          }

          private void riskyStep() {}
        }
        """);

    RuleResult wg013 = wg013Result();

    assertThat(wg013.passed()).isFalse();
    assertThat(wg013.violations()).hasSize(1);
    Violation violation = wg013.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG013");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("control-flow signals");
    assertThat(violation.suggestedFix()).contains("specific checked or application exceptions");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "catch (Throwable)");
  }

  @Test
  void flagsCatchingErrorDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            try {
              riskyStep();
            } catch (Error e) {
              // swallowed
            }
          }

          private void riskyStep() {}
        }
        """);

    RuleResult wg013 = wg013Result();

    assertThat(wg013.violations()).hasSize(1);
    assertThat(wg013.violations().get(0).callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "catch (Error)");
  }

  @Test
  void flagsCatchingThrowableInsideAHelperMethodCalledFromTheWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final PaymentService paymentService = new PaymentService();

          @Override
          public void process() {
            paymentService.chargeCardSafely();
          }
        }
        """);
    writeJavaFile(
        "com/example/PaymentService.java",
        """
        package com.example;

        public class PaymentService {
          void chargeCardSafely() {
            try {
              chargeCard();
            } catch (Throwable t) {
              // swallowed
            }
          }

          private void chargeCard() {}
        }
        """);

    RuleResult wg013 = wg013Result();

    assertThat(wg013.violations()).hasSize(1);
    Violation violation = wg013.violations().get(0);
    assertThat(violation.className()).isEqualTo("PaymentService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "PaymentService.chargeCardSafely()", "catch (Throwable)");
  }

  @Test
  void flagsTheThrowableComponentOfAMultiCatch() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.io.IOException;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            try {
              riskyStep();
            } catch (IOException | Throwable t) {
              // swallowed
            }
          }

          private void riskyStep() throws IOException {}
        }
        """);

    RuleResult wg013 = wg013Result();

    assertThat(wg013.violations()).hasSize(1);
    assertThat(wg013.violations().get(0).callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "catch (Throwable)");
  }

  @Test
  void stillFlagsCatchingThrowableInsideWorkflowSideEffect() throws IOException {
    // Unlike WG001's suppressedContexts: SIDE_EFFECT, WG013 is not suppressed inside a
    // side effect: catching Throwable/Error can swallow Temporal's internal control-flow
    // signals on the workflow thread regardless of which API triggered that code path.
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(Boolean.class, () -> {
              try {
                riskyStep();
              } catch (Throwable t) {
                // swallowed
              }
              return true;
            });
          }

          private void riskyStep() {}
        }
        """);

    RuleResult wg013 = wg013Result();

    assertThat(wg013.violations()).hasSize(1);
  }

  @Test
  void doesNotFlagCatchingASpecificCheckedException() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.io.IOException;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            try {
              riskyStep();
            } catch (IOException e) {
              // handled
            }
          }

          private void riskyStep() throws IOException {}
        }
        """);

    RuleResult wg013 = wg013Result();

    assertThat(wg013.passed()).isTrue();
    assertThat(wg013.violations()).isEmpty();
  }

  @Test
  void doesNotFlagCatchingThrowableInsideAnActivityImplementation() throws IOException {
    writeWorkflowInterface();
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

        import io.temporal.activity.ActivityInterface;
        import io.temporal.activity.ActivityMethod;

        @ActivityInterface
        public interface PaymentActivity {
          @ActivityMethod
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
              reallyChargeCard();
            } catch (Throwable t) {
              // swallowed, but this is Activity code, not subject to WG013
            }
          }

          private void reallyChargeCard() {}
        }
        """);

    RuleResult wg013 = wg013Result();

    assertThat(wg013.passed()).isTrue();
    assertThat(wg013.violations()).isEmpty();
  }
}
