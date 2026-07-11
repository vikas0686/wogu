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

/** Integration-style tests for WG011, exercised through {@link TemporalWorkflowValidator}. */
class SideEffectIoRuleTest {

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

  private RuleResult wg011Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG011"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void flagsSocketConnectionDirectlyInsideSideEffect() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;
        import java.net.Socket;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(Boolean.class, () -> {
              try {
                new Socket("example.com", 80);
              } catch (Exception e) {
                // ignore
              }
              return true;
            });
          }
        }
        """);

    RuleResult wg011 = wg011Result();

    assertThat(wg011.passed()).isFalse();
    assertThat(wg011.violations()).hasSize(1);
    Violation violation = wg011.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG011");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("blocks the workflow task");
    assertThat(violation.suggestedFix()).contains("Activity");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "new Socket()");
  }

  @Test
  void flagsIoInsideAHelperMethodCalledFromTheSideEffectCallback() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final PricingService pricingService = new PricingService();

          @Override
          public void process() {
            Workflow.sideEffect(String.class, () -> pricingService.fetchTierLabel());
          }
        }
        """);
    writeJavaFile(
        "com/example/PricingService.java",
        """
        package com.example;

        import java.io.FileReader;

        public class PricingService {
          String fetchTierLabel() {
            try (FileReader reader = new FileReader("/tmp/tier.txt")) {
              return "loaded";
            } catch (Exception e) {
              return "default";
            }
          }
        }
        """);

    RuleResult wg011 = wg011Result();

    assertThat(wg011.violations()).hasSize(1);
    Violation violation = wg011.violations().get(0);
    assertThat(violation.className()).isEqualTo("PricingService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "PricingService.fetchTierLabel()", "new FileReader()");
  }

  @Test
  void flagsIoInsideMutableSideEffect() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;
        import java.sql.DriverManager;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.mutableSideEffect("price", Integer.class, (o, n) -> !o.equals(n), () -> {
              try {
                DriverManager.getConnection("jdbc:example");
              } catch (Exception e) {
                // ignore
              }
              return 1;
            });
          }
        }
        """);

    RuleResult wg011 = wg011Result();

    assertThat(wg011.violations()).hasSize(1);
    assertThat(wg011.violations().get(0).callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "DriverManager.getConnection()");
  }

  @Test
  void doesNotFlagPureComputationInsideSideEffect() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;
        import java.util.UUID;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(String.class, () -> UUID.randomUUID().toString());
          }
        }
        """);

    RuleResult wg011 = wg011Result();

    assertThat(wg011.passed()).isTrue();
    assertThat(wg011.violations()).isEmpty();
  }

  @Test
  void doesNotFlagTheSameIoCallMadeOutsideASideEffectCallback() throws IOException {
    // WG011 only applies inside sideEffect/mutableSideEffect (requiredContexts); the exact
    // same I/O call made directly in ordinary workflow code is out of scope for this rule.
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.net.Socket;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            try {
              new Socket("example.com", 80);
            } catch (Exception e) {
              // ignore
            }
          }
        }
        """);

    RuleResult wg011 = wg011Result();

    assertThat(wg011.passed()).isTrue();
    assertThat(wg011.violations()).isEmpty();
  }

  @Test
  void doesNotFlagSideEffectCallbackPassedAsAMethodReference() throws IOException {
    // A method reference callback is never visited at all: the call-graph engine only
    // follows MethodCallExpr/ObjectCreationExpr nodes it can resolve, and
    // Workflow.sideEffect(...) itself never resolves to project source (it's the real SDK
    // call), so nothing ever creates a call-graph edge into openSocket()'s body here.
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;
        import java.net.Socket;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(Boolean.class, this::openSocket);
          }

          private boolean openSocket() {
            try {
              new Socket("example.com", 80);
              return true;
            } catch (Exception e) {
              return false;
            }
          }
        }
        """);

    RuleResult wg011 = wg011Result();

    assertThat(wg011.passed()).isTrue();
    assertThat(wg011.violations()).isEmpty();
  }
}
