package io.wogu.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wogu.api.Severity;
import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationResult;
import io.wogu.api.ValidationSummary;
import io.wogu.api.Violation;
import io.wogu.api.WorkflowValidator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationEngineTest {

  private static final ValidationContext CONTEXT =
      DefaultValidationContext.builder()
          .projectName("sample-project")
          .projectDirectory(Path.of("."))
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
            List.of(FixedResultValidator.passing("first"), FixedResultValidator.passing("second")));

    ValidationSummary summary = engine.run(CONTEXT);

    assertThat(summary.results()).extracting(ValidationResult::validatorId).containsExactly("first", "second");
    assertThat(summary.hasBuildFailures()).isFalse();
    assertThat(summary.projectName()).isEqualTo("sample-project");
  }

  @Test
  void aggregatesBuildFailureWhenAnyValidatorReportsAnErrorViolation() {
    Violation violation =
        Violation.builder()
            .validatorId("bad")
            .severity(Severity.ERROR)
            .file(Path.of("Foo.java"))
            .className("Foo")
            .line(1)
            .message("boom")
            .suggestedFix("fix it")
            .build();
    WorkflowValidator failing =
        new FixedResultValidator("bad", ValidationResult.of("bad", List.of(violation), Duration.ofMillis(1)));

    ValidationEngine engine = ValidationEngine.of(List.of(FixedResultValidator.passing("good"), failing));

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
          public ValidationResult validate(ValidationContext context) {
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
}
