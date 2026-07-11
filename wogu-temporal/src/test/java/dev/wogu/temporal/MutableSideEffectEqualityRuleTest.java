package dev.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ast.CompilationUnit;
import dev.wogu.api.RuleResult;
import dev.wogu.api.ValidationContext;
import dev.wogu.api.ValidatorRunOutcome;
import dev.wogu.api.Violation;
import dev.wogu.temporal.callgraph.CallGraphAnalyzer;
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
  void flagsAValueTypeWithOnlyAnOverloadedEqualsThatDoesNotOverrideObjectEquals() throws IOException {
    // Price.equals(Price) is a same-named overload, not an override: without an Object
    // parameter it never participates in the real Object.equals(Object) contract that
    // Workflow.mutableSideEffect()'s updateFunction actually calls through
    // (o, n) -> !o.equals(n), so this type is still identity-compared for that purpose and
    // must be flagged the same as having no equals() at all.
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

          public boolean equals(Price other) {
            return other.amount == amount;
          }
        }
        """);

    RuleResult wg012 = wg012Result();

    assertThat(wg012.violations()).hasSize(1);
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

  @Test
  void honorsRequiredContextsDeclaredOnTheRuleDefinition() throws IOException {
    // Regression test: MutableSideEffectEqualityRule's constructor must actually read
    // suppressedContexts/requiredContexts off the RuleDefinition instead of hardcoding
    // Set.of() for both. wg012.yaml itself declares neither, so this drives the rule type
    // directly with a synthetic definition that does, bypassing TemporalWorkflowValidator's
    // classpath YAML loading (which always loads the shipped wg012.yaml).
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            // NORMAL_WORKFLOW context: not nested inside any sideEffect/mutableSideEffect.
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

    RuleDefinition definitionRequiringSideEffect =
        new RuleDefinition(
            "WG052",
            "mutable-side-effect-equality",
            "Example Rule",
            "Example description",
            "Determinism",
            "WARNING",
            "Temporal Java SDK",
            "0.2.0",
            "docs/rules/WG999.md",
            "Example replacement",
            List.of("io.temporal.workflow.Workflow.mutableSideEffect"),
            List.of(),
            List.of(),
            List.of("SIDE_EFFECT"),
            1,
            List.of());
    TemporalRule rule = new MutableSideEffectEqualityRule(definitionRequiringSideEffect);

    List<CompilationUnit> units = SourceRootParser.parse(List.of(sourceRoot));
    List<ScannedWorkflowClass> workflowClasses = new WorkflowImplementationScanner().scan(units);
    List<Violation> violations =
        rule.evaluate(
            context(),
            workflowClasses,
            new CallGraphAnalyzer(),
            ActivityAwareness.activityBoundary(units),
            TemporalExecutionContexts.entryPoints());

    // The call is only in NORMAL_WORKFLOW context, but the definition requires SIDE_EFFECT —
    // without the fix (requiredContexts hardcoded to Set.of(), meaning "no restriction"),
    // this would incorrectly find 1 violation.
    assertThat(violations).isEmpty();
  }
}
