package io.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationResultTest {

  private static Violation violation(Severity severity) {
    return Violation.builder()
        .validatorId("v")
        .severity(severity)
        .file(Path.of("Foo.java"))
        .className("Foo")
        .line(1)
        .message("m")
        .suggestedFix("f")
        .build();
  }

  @Test
  void passesWhenThereAreNoViolations() {
    ValidationResult result = ValidationResult.of("v", List.of(), Duration.ofMillis(5));

    assertThat(result.passed()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void passesWhenOnlyWarningsAndInfoArePresent() {
    ValidationResult result =
        ValidationResult.of(
            "v", List.of(violation(Severity.WARNING), violation(Severity.INFO)), Duration.ofMillis(5));

    assertThat(result.passed()).isTrue();
  }

  @Test
  void failsWhenAnErrorSeverityViolationIsPresent() {
    ValidationResult result =
        ValidationResult.of(
            "v", List.of(violation(Severity.WARNING), violation(Severity.ERROR)), Duration.ofMillis(5));

    assertThat(result.passed()).isFalse();
  }

  @Test
  void violationsListIsImmutable() {
    ValidationResult result = ValidationResult.of("v", List.of(violation(Severity.ERROR)), Duration.ofMillis(1));

    assertThat(result.violations()).isUnmodifiable();
  }
}
