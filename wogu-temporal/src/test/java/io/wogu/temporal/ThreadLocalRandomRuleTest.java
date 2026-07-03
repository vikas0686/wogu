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

/** Integration-style tests for WG006, exercised through {@link TemporalWorkflowValidator}. */
class ThreadLocalRandomRuleTest {

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

  private RuleResult wg006Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG006"))
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
  void flagsThreadLocalRandomCurrentDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.util.concurrent.ThreadLocalRandom;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            int value = ThreadLocalRandom.current().nextInt();
          }
        }
        """);

    RuleResult wg006 = wg006Result();

    assertThat(wg006.passed()).isFalse();
    assertThat(wg006.violations()).hasSize(1);
    Violation violation = wg006.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG006");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.suggestedFix()).contains("Workflow.newRandom()");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "ThreadLocalRandom.current()");
  }

  @Test
  void flagsThreadLocalRandomInsideAServiceCalledByTheWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final PaymentService paymentService = new PaymentService();

          @Override
          public void process() {
            paymentService.pickDiscount();
          }
        }
        """);
    writeJavaFile(
        "com/example/PaymentService.java",
        """
        package com.example;

        import java.util.concurrent.ThreadLocalRandom;

        public class PaymentService {
          int pickDiscount() {
            return ThreadLocalRandom.current().nextInt(100);
          }
        }
        """);

    RuleResult wg006 = wg006Result();

    assertThat(wg006.violations()).hasSize(1);
    Violation violation = wg006.violations().get(0);
    assertThat(violation.className()).isEqualTo("PaymentService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "PaymentService.pickDiscount()", "ThreadLocalRandom.current()");
  }

  @Test
  void flagsThreadLocalRandomReachableThroughANestedServiceCall() throws IOException {
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
          private final DiscountService discountService = new DiscountService();

          void createOrder() {
            discountService.pickDiscount();
          }
        }
        """);
    writeJavaFile(
        "com/example/DiscountService.java",
        """
        package com.example;

        import java.util.concurrent.ThreadLocalRandom;

        public class DiscountService {
          int pickDiscount() {
            return ThreadLocalRandom.current().nextInt();
          }
        }
        """);

    RuleResult wg006 = wg006Result();

    assertThat(wg006.violations()).hasSize(1);
    Violation violation = wg006.violations().get(0);
    assertThat(violation.className()).isEqualTo("DiscountService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.process()",
            "OrderService.createOrder()",
            "DiscountService.pickDiscount()",
            "ThreadLocalRandom.current()");
  }

  @Test
  void doesNotFlagThreadLocalRandomInsideAnActivityImplementationInvokedThroughItsInterface() throws IOException {
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

        import java.util.concurrent.ThreadLocalRandom;

        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public void chargeCard() {
            int value = ThreadLocalRandom.current().nextInt();
          }
        }
        """);

    RuleResult wg006 = wg006Result();

    assertThat(wg006.passed()).isTrue();
    assertThat(wg006.violations()).isEmpty();
  }

  @Test
  void doesNotFlagThreadLocalRandomOutsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PlainDiscountPicker.java",
        """
        package com.example;

        import java.util.concurrent.ThreadLocalRandom;

        public class PlainDiscountPicker {
          public int pick() {
            return ThreadLocalRandom.current().nextInt();
          }
        }
        """);

    RuleResult wg006 = wg006Result();

    assertThat(wg006.passed()).isTrue();
    assertThat(wg006.violations()).isEmpty();
  }
}
