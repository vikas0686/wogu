package dev.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wogu.api.CallPathFrame;
import dev.wogu.api.Rule;
import dev.wogu.api.RuleCategory;
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

class TemporalWorkflowValidatorTest {

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

  @Test
  void declaresWG001ThroughWG011AsItsRules() {
    assertThat(validator.rules())
        .extracting(Rule::id)
        .containsExactlyInAnyOrder(
            "WG001", "WG002", "WG003", "WG004", "WG005", "WG006", "WG007", "WG008", "WG009", "WG010", "WG011");
  }

  @Test
  void everyDeclaredRuleIdFallsWithinItsCategorysReservedNumericRange() {
    for (Rule rule : validator.rules()) {
      assertThat(rule.category()).isEqualTo(RuleCategory.DETERMINISM);
      assertThat(rule.category().containsRuleId(rule.id())).isTrue();
    }
  }

  @Test
  void flagsDirectUuidRandomUuidInsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface PaymentWorkflow {
          void pay();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.util.UUID;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void pay() {
            String id = UUID.randomUUID().toString();
          }
        }
        """);

    ValidatorRunOutcome outcome = validator.validate(context());

    assertThat(outcome.scannedElementCount()).isEqualTo(1);
    RuleResult wg001 = onlyResult(outcome);
    assertThat(wg001.passed()).isFalse();
    assertThat(wg001.violations()).hasSize(1);
    Violation violation = wg001.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG001");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("NonDeterministicException");
    assertThat(violation.suggestedFix()).contains("Workflow.randomUUID()");
    assertThat(violation.callPath()).extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.pay()", "UUID.randomUUID()");
  }

  @Test
  void flagsUuidRandomUuidReachableThroughMultipleMethodHops() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;
        import io.temporal.workflow.WorkflowMethod;

        @WorkflowInterface
        public interface PaymentWorkflow {
          @WorkflowMethod
          void processPayment();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final OrderService orderService = new OrderService();

          @Override
          public void processPayment() {
            orderService.createOrder();
          }
        }
        """);
    writeJavaFile(
        "com/example/OrderService.java",
        """
        package com.example;

        public class OrderService {
          private final CustomerService customerService = new CustomerService();

          void createOrder() {
            customerService.generateId();
          }
        }
        """);
    writeJavaFile(
        "com/example/CustomerService.java",
        """
        package com.example;

        import java.util.UUID;

        public class CustomerService {
          void generateId() {
            UUID.randomUUID();
          }
        }
        """);

    RuleResult wg001 = onlyResult(validator.validate(context()));

    assertThat(wg001.violations()).hasSize(1);
    Violation violation = wg001.violations().get(0);
    assertThat(violation.className()).isEqualTo("CustomerService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.processPayment()",
            "OrderService.createOrder()",
            "CustomerService.generateId()",
            "UUID.randomUUID()");
  }

  @Test
  void doesNotFlagTemporalsDeterministicWorkflowRandomUuid() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface PaymentWorkflow {
          void pay();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void pay() {
            String id = Workflow.randomUUID().toString();
          }
        }
        """);

    RuleResult wg001 = onlyResult(validator.validate(context()));

    assertThat(wg001.passed()).isTrue();
    assertThat(wg001.violations()).isEmpty();
  }

  @Test
  void doesNotFlagUuidRandomUuidOutsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PlainIdGenerator.java",
        """
        package com.example;

        import java.util.UUID;

        public class PlainIdGenerator {
          public String next() {
            return UUID.randomUUID().toString();
          }
        }
        """);

    RuleResult wg001 = onlyResult(validator.validate(context()));

    assertThat(wg001.passed()).isTrue();
    assertThat(wg001.violations()).isEmpty();
  }

  @Test
  void doesNotFlagAViolationOnlyReachableByResolvingDirectlyIntoAnAnnotatedActivityImplementation() throws IOException {
    // Unlike the "natural boundary" case (a field typed as the Activity *interface*, where
    // resolution dead-ends on the interface's bodyless method with no help needed from
    // WoGu), this field is typed as the Activity *implementation* directly, so resolution
    // genuinely reaches real, callable source. Only the explicit @ActivityInterface /
    // @ActivityMethod annotation check stops traversal from continuing into it.
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface PaymentWorkflow {
          void pay();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final PaymentActivityImpl activity = new PaymentActivityImpl();

          @Override
          public void pay() {
            activity.generateId();
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
          String generateId();
        }
        """);
    writeJavaFile(
        "com/example/PaymentActivityImpl.java",
        """
        package com.example;

        import java.util.UUID;

        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public String generateId() {
            return UUID.randomUUID().toString();
          }
        }
        """);

    RuleResult wg001 = onlyResult(validator.validate(context()));

    assertThat(wg001.passed()).isTrue();
    assertThat(wg001.violations()).isEmpty();
  }

  private static RuleResult onlyResult(ValidatorRunOutcome outcome) {
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG001"))
        .findFirst()
        .orElseThrow();
  }
}
