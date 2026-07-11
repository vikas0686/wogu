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

/** Integration-style tests for WG005, exercised through {@link TemporalWorkflowValidator}. */
class JavaUtilRandomRuleTest {

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

  private RuleResult wg005Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG005"))
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
  void flagsConstructingARandomDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.util.Random;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Random random = new Random();
          }
        }
        """);

    RuleResult wg005 = wg005Result();

    assertThat(wg005.passed()).isFalse();
    assertThat(wg005.violations()).hasSize(1);
    Violation violation = wg005.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG005");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.suggestedFix()).contains("Workflow.newRandom()");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "new Random()");
  }

  @Test
  void flagsCallingNextIntOnARandomInsideAServiceCalledByTheWorkflow() throws IOException {
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

        import java.util.Random;

        public class PaymentService {
          int pickDiscount() {
            Random random = new Random();
            return random.nextInt();
          }
        }
        """);

    RuleResult wg005 = wg005Result();

    // The construction and the resolved nextInt() call are each their own violation: one
    // flags "new Random()", the other flags a reachable call resolving to Random.nextInt().
    assertThat(wg005.violations()).hasSize(2);
    assertThat(wg005.violations())
        .extracting(v -> v.callPath().get(v.callPath().size() - 1).displayName())
        .containsExactlyInAnyOrder("new Random()", "Random.nextInt()");
  }

  @Test
  void flagsARandomConstructedInsideANestedService() throws IOException {
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

        import java.util.Random;

        public class DiscountService {
          double pickDiscount() {
            return new Random().nextDouble();
          }
        }
        """);

    RuleResult wg005 = wg005Result();

    // "new Random()" and the resolved ".nextDouble()" call are each their own violation.
    assertThat(wg005.violations()).hasSize(2);
    assertThat(wg005.violations()).allSatisfy(v -> assertThat(v.className()).isEqualTo("DiscountService"));
    assertThat(wg005.violations())
        .extracting(v -> v.callPath().get(v.callPath().size() - 1).displayName())
        .containsExactlyInAnyOrder("new Random()", "Random.nextDouble()");
  }

  @Test
  void doesNotFlagRandomUsageInsideAnActivityImplementationInvokedThroughItsInterface() throws IOException {
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

        import java.util.Random;

        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public void chargeCard() {
            Random random = new Random();
            random.nextInt();
          }
        }
        """);

    RuleResult wg005 = wg005Result();

    assertThat(wg005.passed()).isTrue();
    assertThat(wg005.violations()).isEmpty();
  }

  @Test
  void doesNotFlagRandomUsageInsideWorkflowSideEffect() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;
        import java.util.Random;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(Integer.class, () -> {
              Random random = new Random();
              return random.nextInt();
            });
          }
        }
        """);

    RuleResult wg005 = wg005Result();

    assertThat(wg005.passed()).isTrue();
    assertThat(wg005.violations()).isEmpty();
  }

  @Test
  void stillFlagsRandomUsageOutsideWorkflowSideEffectInTheSameWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;
        import java.util.Random;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(Integer.class, () -> {
              Random random = new Random();
              return random.nextInt();
            });
            new Random().nextDouble();
          }
        }
        """);

    RuleResult wg005 = wg005Result();

    assertThat(wg005.passed()).isFalse();
    assertThat(wg005.violations())
        .extracting(v -> v.callPath().get(v.callPath().size() - 1).displayName())
        .containsExactlyInAnyOrder("new Random()", "Random.nextDouble()");
  }

  @Test
  void doesNotFlagRandomUsageOutsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PlainDiscountPicker.java",
        """
        package com.example;

        import java.util.Random;

        public class PlainDiscountPicker {
          public double pick() {
            return new Random().nextDouble();
          }
        }
        """);

    RuleResult wg005 = wg005Result();

    assertThat(wg005.passed()).isTrue();
    assertThat(wg005.violations()).isEmpty();
  }
}
