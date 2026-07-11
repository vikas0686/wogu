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

/** Integration-style tests for WG007, exercised through {@link TemporalWorkflowValidator}. */
class SecureRandomRuleTest {

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

  private RuleResult wg007Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG007"))
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
  void flagsConstructingASecureRandomDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.security.SecureRandom;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            SecureRandom random = new SecureRandom();
          }
        }
        """);

    RuleResult wg007 = wg007Result();

    assertThat(wg007.passed()).isFalse();
    assertThat(wg007.violations()).hasSize(1);
    Violation violation = wg007.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG007");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.suggestedFix()).contains("Workflow.newRandom()");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "new SecureRandom()");
  }

  @Test
  void flagsSecureRandomNextBytesInsideAServiceCalledByTheWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final PaymentService paymentService = new PaymentService();

          @Override
          public void process() {
            paymentService.generateToken();
          }
        }
        """);
    writeJavaFile(
        "com/example/PaymentService.java",
        """
        package com.example;

        import java.security.SecureRandom;

        public class PaymentService {
          byte[] generateToken() {
            byte[] bytes = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(bytes);
            return bytes;
          }
        }
        """);

    RuleResult wg007 = wg007Result();

    // "new SecureRandom()" and the resolved ".nextBytes()" call are each their own violation.
    assertThat(wg007.violations()).hasSize(2);
    assertThat(wg007.violations()).allSatisfy(v -> assertThat(v.className()).isEqualTo("PaymentService"));
    assertThat(wg007.violations())
        .extracting(v -> v.callPath().get(v.callPath().size() - 1).displayName())
        .containsExactlyInAnyOrder("new SecureRandom()", "SecureRandom.nextBytes()");
  }

  @Test
  void flagsASecureRandomConstructedInsideANestedService() throws IOException {
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
          private final TokenService tokenService = new TokenService();

          void createOrder() {
            tokenService.generateToken();
          }
        }
        """);
    writeJavaFile(
        "com/example/TokenService.java",
        """
        package com.example;

        import java.security.SecureRandom;

        public class TokenService {
          SecureRandom generateToken() {
            return new SecureRandom();
          }
        }
        """);

    RuleResult wg007 = wg007Result();

    assertThat(wg007.violations()).hasSize(1);
    Violation violation = wg007.violations().get(0);
    assertThat(violation.className()).isEqualTo("TokenService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.process()", "OrderService.createOrder()", "TokenService.generateToken()", "new SecureRandom()");
  }

  @Test
  void doesNotFlagSecureRandomUsageInsideAnActivityImplementationInvokedThroughItsInterface() throws IOException {
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

        import java.security.SecureRandom;

        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public void chargeCard() {
            SecureRandom random = new SecureRandom();
          }
        }
        """);

    RuleResult wg007 = wg007Result();

    assertThat(wg007.passed()).isTrue();
    assertThat(wg007.violations()).isEmpty();
  }

  @Test
  void doesNotFlagSecureRandomUsageInsideWorkflowSideEffect() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;
        import java.security.SecureRandom;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(byte[].class, () -> {
              byte[] token = new byte[16];
              new SecureRandom().nextBytes(token);
              return token;
            });
          }
        }
        """);

    RuleResult wg007 = wg007Result();

    assertThat(wg007.passed()).isTrue();
    assertThat(wg007.violations()).isEmpty();
  }

  @Test
  void stillFlagsSecureRandomUsageOutsideWorkflowSideEffectInTheSameWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;
        import java.security.SecureRandom;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(byte[].class, () -> {
              byte[] token = new byte[16];
              new SecureRandom().nextBytes(token);
              return token;
            });
            new SecureRandom();
          }
        }
        """);

    RuleResult wg007 = wg007Result();

    assertThat(wg007.passed()).isFalse();
    assertThat(wg007.violations())
        .extracting(v -> v.callPath().get(v.callPath().size() - 1).displayName())
        .containsExactly("new SecureRandom()");
  }

  @Test
  void doesNotFlagSecureRandomUsageOutsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PlainTokenGenerator.java",
        """
        package com.example;

        import java.security.SecureRandom;

        public class PlainTokenGenerator {
          public SecureRandom random() {
            return new SecureRandom();
          }
        }
        """);

    RuleResult wg007 = wg007Result();

    assertThat(wg007.passed()).isTrue();
    assertThat(wg007.violations()).isEmpty();
  }
}
