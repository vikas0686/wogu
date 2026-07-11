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

/** Integration-style tests for WG009, exercised through {@link TemporalWorkflowValidator}. */
class SystemGetPropertyRuleTest {

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

  private RuleResult wg009Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG009"))
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
  void flagsSystemGetPropertyDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            String home = System.getProperty("user.home");
          }
        }
        """);

    RuleResult wg009 = wg009Result();

    assertThat(wg009.passed()).isFalse();
    assertThat(wg009.violations()).hasSize(1);
    Violation violation = wg009.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG009");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("System properties");
    assertThat(violation.suggestedFix()).contains("Activity");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "System.getProperty()");
  }

  @Test
  void flagsSystemGetPropertyInsideAServiceCalledByTheWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final ConfigService configService = new ConfigService();

          @Override
          public void process() {
            configService.userHome();
          }
        }
        """);
    writeJavaFile(
        "com/example/ConfigService.java",
        """
        package com.example;

        public class ConfigService {
          String userHome() {
            return System.getProperty("user.home");
          }
        }
        """);

    RuleResult wg009 = wg009Result();

    assertThat(wg009.violations()).hasSize(1);
    Violation violation = wg009.violations().get(0);
    assertThat(violation.className()).isEqualTo("ConfigService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "ConfigService.userHome()", "System.getProperty()");
  }

  @Test
  void flagsSystemGetPropertyReachableThroughANestedServiceCall() throws IOException {
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
            configService.userHome();
          }
        }
        """);
    writeJavaFile(
        "com/example/ConfigService.java",
        """
        package com.example;

        public class ConfigService {
          String userHome() {
            return System.getProperty("user.home");
          }
        }
        """);

    RuleResult wg009 = wg009Result();

    assertThat(wg009.violations()).hasSize(1);
    Violation violation = wg009.violations().get(0);
    assertThat(violation.className()).isEqualTo("ConfigService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.process()", "OrderService.createOrder()", "ConfigService.userHome()", "System.getProperty()");
  }

  @Test
  void doesNotFlagSystemGetPropertyInsideAnActivityImplementationInvokedThroughItsInterface() throws IOException {
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
            String home = System.getProperty("user.home");
          }
        }
        """);

    RuleResult wg009 = wg009Result();

    assertThat(wg009.passed()).isTrue();
    assertThat(wg009.violations()).isEmpty();
  }

  @Test
  void doesNotFlagSystemGetPropertyOutsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PlainConfigReader.java",
        """
        package com.example;

        public class PlainConfigReader {
          public String userHome() {
            return System.getProperty("user.home");
          }
        }
        """);

    RuleResult wg009 = wg009Result();

    assertThat(wg009.passed()).isTrue();
    assertThat(wg009.violations()).isEmpty();
  }
}
