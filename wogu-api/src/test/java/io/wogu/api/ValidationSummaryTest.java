package io.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationSummaryTest {

  private static Violation errorViolation() {
    return Violation.builder()
        .validatorId("v1")
        .severity(Severity.ERROR)
        .file(Path.of("Foo.java"))
        .className("Foo")
        .line(1)
        .message("m")
        .suggestedFix("f")
        .build();
  }

  @Test
  void hasNoBuildFailuresWhenAllValidatorsPass() {
    ValidationResult passing = ValidationResult.of("v1", List.of(), Duration.ofMillis(2));
    ValidationSummary summary =
        ValidationSummary.of("sample-project", Instant.now(), List.of(passing), Duration.ofMillis(2));

    assertThat(summary.hasBuildFailures()).isFalse();
    assertThat(summary.allViolations()).isEmpty();
  }

  @Test
  void hasBuildFailuresWhenAnyValidatorFails() {
    ValidationResult failing = ValidationResult.of("v1", List.of(errorViolation()), Duration.ofMillis(3));
    ValidationResult passing = ValidationResult.of("v2", List.of(), Duration.ofMillis(1));
    ValidationSummary summary =
        ValidationSummary.of(
            "sample-project", Instant.now(), List.of(failing, passing), Duration.ofMillis(4));

    assertThat(summary.hasBuildFailures()).isTrue();
    assertThat(summary.allViolations()).hasSize(1);
  }

  @Test
  void resultsListIsImmutable() {
    ValidationSummary summary =
        ValidationSummary.of("p", Instant.now(), List.of(), Duration.ZERO);

    assertThat(summary.results()).isUnmodifiable();
  }
}
