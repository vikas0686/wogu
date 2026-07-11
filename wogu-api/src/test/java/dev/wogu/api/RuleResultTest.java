package dev.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleResultTest {

  private static Rule ruleOf(Severity severity) {
    return Rule.builder()
        .id("WG001")
        .title("UUID.randomUUID() inside Workflow")
        .category(RuleCategory.DETERMINISM)
        .severity(severity)
        .engine("Temporal Java SDK")
        .sinceVersion("0.1.0")
        .documentationReference("docs/rules/WG001.md")
        .build();
  }

  private static Violation violation(Severity severity) {
    return Violation.builder()
        .rule(ruleOf(severity))
        .file(Path.of("Foo.java"))
        .className("Foo")
        .line(1)
        .message("m")
        .suggestedFix("f")
        .build();
  }

  @Test
  void passesWhenThereAreNoViolations() {
    RuleResult result = RuleResult.of(ruleOf(Severity.ERROR), List.of(), Duration.ofMillis(5));

    assertThat(result.passed()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void passesWhenOnlyWarningsAndInfoArePresent() {
    RuleResult result =
        RuleResult.of(
            ruleOf(Severity.WARNING),
            List.of(violation(Severity.WARNING), violation(Severity.INFO)),
            Duration.ofMillis(5));

    assertThat(result.passed()).isTrue();
  }

  @Test
  void failsWhenAnErrorSeverityViolationIsPresent() {
    RuleResult result =
        RuleResult.of(
            ruleOf(Severity.ERROR),
            List.of(violation(Severity.WARNING), violation(Severity.ERROR)),
            Duration.ofMillis(5));

    assertThat(result.passed()).isFalse();
  }

  @Test
  void violationsListIsImmutable() {
    RuleResult result = RuleResult.of(ruleOf(Severity.ERROR), List.of(violation(Severity.ERROR)), Duration.ofMillis(1));

    assertThat(result.violations()).isUnmodifiable();
  }
}
