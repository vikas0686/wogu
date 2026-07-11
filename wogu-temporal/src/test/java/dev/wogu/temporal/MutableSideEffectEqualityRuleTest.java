package dev.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Integration-style tests for WG012, exercised through {@link TemporalWorkflowValidator}. */
class MutableSideEffectEqualityRuleTest {

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

  private RuleResult wg012Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG012"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void flagsAValueTypeWithNoEqualsOverride() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.mutableSideEffect("price", Price.class, (o, n) -> !o.equals(n), Price::new);
          }
        }
        """);
    writeJavaFile(
        "com/example/Price.java",
        """
        package com.example;

        public class Price {
          double amount;
        }
        """);

    RuleResult wg012 = wg012Result();

    // WG012 is WARNING severity, which never blocks the build (Severity.blocksBuild() is
    // true only for ERROR) — passed() would be true either way, so the real assertion is
    // the violation count, not passed().
    assertThat(wg012.violations()).hasSize(1);
    Violation violation = wg012.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG012");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("identity-based Object.equals()");
    assertThat(violation.suggestedFix()).contains("value-based equals()");
  }

  @Test
  void doesNotFlagAValueTypeWithItsOwnEqualsOverride() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.mutableSideEffect("price", Price.class, (o, n) -> !o.equals(n), Price::new);
          }
        }
        """);
    writeJavaFile(
        "com/example/Price.java",
        """
        package com.example;

        import java.util.Objects;

        public class Price {
          double amount;

          @Override
          public boolean equals(Object o) {
            return o instanceof Price other && other.amount == amount;
          }

          @Override
          public int hashCode() {
            return Objects.hash(amount);
          }
        }
        """);

    RuleResult wg012 = wg012Result();

    assertThat(wg012.passed()).isTrue();
    assertThat(wg012.violations()).isEmpty();
  }

  @Test
  void doesNotFlagAValueTypeInheritingEqualsFromANonObjectSuperclass() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.mutableSideEffect("price", Price.class, (o, n) -> !o.equals(n), Price::new);
          }
        }
        """);
    writeJavaFile(
        "com/example/AbstractMoney.java",
        """
        package com.example;

        import java.util.Objects;

        public abstract class AbstractMoney {
          double amount;

          @Override
          public boolean equals(Object o) {
            return o instanceof AbstractMoney other && other.amount == amount;
          }

          @Override
          public int hashCode() {
            return Objects.hash(amount);
          }
        }
        """);
    writeJavaFile(
        "com/example/Price.java",
        """
        package com.example;

        public class Price extends AbstractMoney {
        }
        """);

    RuleResult wg012 = wg012Result();

    assertThat(wg012.passed()).isTrue();
    assertThat(wg012.violations()).isEmpty();
  }

  @Test
  void doesNotFlagARecordValueType() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.mutableSideEffect("price", Price.class, (o, n) -> !o.equals(n), () -> new Price(1.0));
          }
        }
        """);
    writeJavaFile(
        "com/example/Price.java",
        """
        package com.example;

        public record Price(double amount) {
        }
        """);

    RuleResult wg012 = wg012Result();

    assertThat(wg012.passed()).isTrue();
    assertThat(wg012.violations()).isEmpty();
  }

  @Test
  void doesNotFlagAJdkWrapperValueType() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.mutableSideEffect("price", Integer.class, (o, n) -> !o.equals(n), () -> 1);
          }
        }
        """);

    RuleResult wg012 = wg012Result();

    assertThat(wg012.passed()).isTrue();
    assertThat(wg012.violations()).isEmpty();
  }

  @Test
  void doesNotFlagPlainWorkflowSideEffect() throws IOException {
    // Sanity check: Workflow.sideEffect(...) (no updateFunction, no convergence concept at
    // all) must never be matched by a target built specifically for mutableSideEffect.
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Workflow.sideEffect(Price.class, Price::new);
          }
        }
        """);
    writeJavaFile(
        "com/example/Price.java",
        """
        package com.example;

        public class Price {
          double amount;
        }
        """);

    RuleResult wg012 = wg012Result();

    assertThat(wg012.passed()).isTrue();
    assertThat(wg012.violations()).isEmpty();
  }
}
