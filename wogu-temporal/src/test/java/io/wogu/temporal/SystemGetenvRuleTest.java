package io.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import io.wogu.api.CallPathFrame;
import io.wogu.api.RuleResult;
import io.wogu.api.ValidationContext;
import io.wogu.api.ValidatorRunOutcome;
import io.wogu.api.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration-style tests for WG008, exercised through {@link TemporalWorkflowValidator}. */
class SystemGetenvRuleTest {

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

  private RuleResult wg008Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG008"))
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
  void flagsSystemGetenvDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            String region = System.getenv("REGION");
          }
        }
        """);

    RuleResult wg008 = wg008Result();

    assertThat(wg008.passed()).isFalse();
    assertThat(wg008.violations()).hasSize(1);
    Violation violation = wg008.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG008");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("Environment variables");
    assertThat(violation.suggestedFix()).contains("Activity");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "System.getenv()");
  }

  @Test
  void flagsSystemGetenvInsideAServiceCalledByTheWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final ConfigService configService = new ConfigService();

          @Override
          public void process() {
            configService.region();
          }
        }
        """);
    writeJavaFile(
        "com/example/ConfigService.java",
        """
        package com.example;

        public class ConfigService {
          String region() {
            return System.getenv("REGION");
          }
        }
        """);

    RuleResult wg008 = wg008Result();

    assertThat(wg008.violations()).hasSize(1);
    Violation violation = wg008.violations().get(0);
    assertThat(violation.className()).isEqualTo("ConfigService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "ConfigService.region()", "System.getenv()");
  }

  @Test
  void flagsSystemGetenvReachableThroughANestedServiceCall() throws IOException {
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
          private final ConfigService configService = new ConfigService();

          void createOrder() {
            configService.region();
          }
        }
        """);
    writeJavaFile(
        "com/example/ConfigService.java",
        """
        package com.example;

        public class ConfigService {
          String region() {
            return System.getenv("REGION");
          }
        }
        """);

    RuleResult wg008 = wg008Result();

    assertThat(wg008.violations()).hasSize(1);
    Violation violation = wg008.violations().get(0);
    assertThat(violation.className()).isEqualTo("ConfigService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.process()", "OrderService.createOrder()", "ConfigService.region()", "System.getenv()");
  }

  @Test
  void doesNotFlagSystemGetenvInsideAnActivityImplementationInvokedThroughItsInterface() throws IOException {
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

        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public void chargeCard() {
            String region = System.getenv("REGION");
          }
        }
        """);

    RuleResult wg008 = wg008Result();

    assertThat(wg008.passed()).isTrue();
    assertThat(wg008.violations()).isEmpty();
  }

  @Test
  void doesNotFlagSystemGetenvOutsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PlainConfigReader.java",
        """
        package com.example;

        public class PlainConfigReader {
          public String region() {
            return System.getenv("REGION");
          }
        }
        """);

    RuleResult wg008 = wg008Result();

    assertThat(wg008.passed()).isTrue();
    assertThat(wg008.violations()).isEmpty();
  }
}
