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

/** Integration-style tests for WG003, exercised through {@link TemporalWorkflowValidator}. */
class NonDeterministicTimeApiRuleTest {

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

  private RuleResult wg003Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG003"))
        .findFirst()
        .orElseThrow();
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

  @Test
  void flagsSystemCurrentTimeMillisDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            long now = System.currentTimeMillis();
          }
        }
        """);

    RuleResult wg003 = wg003Result();

    assertThat(wg003.passed()).isFalse();
    assertThat(wg003.violations()).hasSize(1);
    Violation violation = wg003.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG003");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("current wall-clock time");
    assertThat(violation.suggestedFix()).contains("Workflow.currentTimeMillis()");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "System.currentTimeMillis()");
  }

  @Test
  void flagsInstantNowInsideAHelperClassCalledByTheWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final PaymentService paymentService = new PaymentService();

          @Override
          public void process() {
            paymentService.currentTime();
          }
        }
        """);
    writeJavaFile(
        "com/example/PaymentService.java",
        """
        package com.example;

        import java.time.Instant;

        public class PaymentService {
          long currentTime() {
            return Instant.now().toEpochMilli();
          }
        }
        """);

    RuleResult wg003 = wg003Result();

    assertThat(wg003.violations()).hasSize(1);
    Violation violation = wg003.violations().get(0);
    assertThat(violation.className()).isEqualTo("PaymentService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "PaymentService.currentTime()", "Instant.now()");
  }

  @Test
  void flagsLocalDateNowInsideANestedService() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final OrderService orderService = new OrderService();

          @Override
          public void process() {
            orderService.createOrder();
          }
        }
        """);
    writeJavaFile(
        "com/example/OrderService.java",
        """
        package com.example;

        public class OrderService {
          private final DateService dateService = new DateService();

          void createOrder() {
            dateService.today();
          }
        }
        """);
    writeJavaFile(
        "com/example/DateService.java",
        """
        package com.example;

        import java.time.LocalDate;

        public class DateService {
          LocalDate today() {
            return LocalDate.now();
          }
        }
        """);

    RuleResult wg003 = wg003Result();

    assertThat(wg003.violations()).hasSize(1);
    Violation violation = wg003.violations().get(0);
    assertThat(violation.className()).isEqualTo("DateService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.process()", "OrderService.createOrder()", "DateService.today()", "LocalDate.now()");
  }

  @Test
  void doesNotFlagWorkflowCurrentTimeMillis() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            long now = Workflow.currentTimeMillis();
          }
        }
        """);

    RuleResult wg003 = wg003Result();

    assertThat(wg003.passed()).isTrue();
    assertThat(wg003.violations()).isEmpty();
  }

  @Test
  void doesNotFlagATimeApiInsideAnActivityImplementationInvokedThroughItsInterface() throws IOException {
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

        public interface PaymentActivity {
          void chargeCard();
        }
        """);
    writeJavaFile(
        "com/example/PaymentActivityImpl.java",
        """
        package com.example;

        import java.time.Instant;

        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public void chargeCard() {
            Instant chargedAt = Instant.now();
          }
        }
        """);

    RuleResult wg003 = wg003Result();

    assertThat(wg003.passed()).isTrue();
    assertThat(wg003.violations()).isEmpty();
  }
}
