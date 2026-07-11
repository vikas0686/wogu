package dev.wogu.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.wogu.api.Rule;
import dev.wogu.api.RuleResult;
import dev.wogu.api.ValidationContext;
import dev.wogu.api.ValidationSummary;
import dev.wogu.api.ValidatorRunOutcome;
import dev.wogu.api.Violation;
import dev.wogu.api.WorkflowValidator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationEngineTest {

  private static final ValidationContext CONTEXT =
      DefaultValidationContext.builder()
          .projectName("sample-project")
          .projectDirectory(Path.of("."))
          .buildTool("Maven")
          .build();

  @Test
  void discoversValidatorsRegisteredViaServiceLoader() {
    ValidationEngine engine = ValidationEngine.discover();

    assertThat(engine.validators())
        .extracting(WorkflowValidator::id)
        .contains("service-loader-discovered");
  }

  @Test
  void runsExplicitlyRegisteredValidatorsInOrder() {
    ValidationEngine engine =
        ValidationEngine.of(
            List.of(
                FixedResultValidator.passing("first", "WG001"),
                FixedResultValidator.passing("second", "WG002")));

    ValidationSummary summary = engine.run(CONTEXT);

    assertThat(summary.results()).extracting(r -> r.rule().id()).containsExactly("WG001", "WG002");
    assertThat(summary.hasBuildFailures()).isFalse();
    assertThat(summary.projectName()).isEqualTo("sample-project");
    assertThat(summary.buildTool()).isEqualTo("Maven");
  }

  @Test
  void aggregatesBuildFailureWhenAnyValidatorReportsAnErrorViolation() {
    Rule rule = FixedResultValidator.testRule("WG003");
    Violation violation =
        Violation.builder()
            .rule(rule)
            .file(Path.of("Foo.java"))
            .className("Foo")
            .line(1)
            .message("boom")
            .suggestedFix("fix it")
            .build();
    WorkflowValidator failing =
        new FixedResultValidator("bad", RuleResult.of(rule, List.of(violation), Duration.ofMillis(1)));

    ValidationEngine engine = ValidationEngine.of(List.of(FixedResultValidator.passing("good", "WG001"), failing));

    ValidationSummary summary = engine.run(CONTEXT);

    assertThat(summary.hasBuildFailures()).isTrue();
    assertThat(summary.allViolations()).containsExactly(violation);
  }

  @Test
  void wrapsValidatorExceptionsWithValidatorId() {
    WorkflowValidator broken =
        new WorkflowValidator() {
          @Override
          public String id() {
            return "broken";
          }

          @Override
          public String description() {
            return "always throws";
          }

          @Override
          public List<Rule> rules() {
            return List.of(FixedResultValidator.testRule("WG001"));
          }

          @Override
          public ValidatorRunOutcome validate(ValidationContext context) {
            throw new IllegalStateException("cannot read source root");
          }
        };

    ValidationEngine engine = ValidationEngine.of(List.of(broken));

    assertThatThrownBy(() -> engine.run(CONTEXT))
        .isInstanceOf(ValidatorExecutionException.class)
        .hasMessageContaining("broken")
        .satisfies(e -> assertThat(((ValidatorExecutionException) e).validatorId()).isEqualTo("broken"));
  }

  @Test
  void producesEmptySummaryWhenNoValidatorsAreRegistered() {
    ValidationEngine engine = ValidationEngine.of(List.of());

    ValidationSummary summary = engine.run(CONTEXT);

    assertThat(summary.results()).isEmpty();
    assertThat(summary.hasBuildFailures()).isFalse();
  }

  @Test
  void exposesRuntimeBuildMetadataOnTheSummary() {
    ValidationEngine engine = ValidationEngine.of(List.of());

    ValidationSummary summary = engine.run(CONTEXT);

    assertThat(summary.javaVersion()).isEqualTo(System.getProperty("java.version"));
    assertThat(summary.woguVersion()).isEqualTo("development");
  }
}
