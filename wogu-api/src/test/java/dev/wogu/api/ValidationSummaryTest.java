package dev.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationSummaryTest {

  private static Rule rule(String id) {
    return Rule.builder()
        .id(id)
        .title("Test rule")
        .category(RuleCategory.DETERMINISM)
        .severity(Severity.ERROR)
        .engine("Temporal Java SDK")
        .sinceVersion("0.1.0")
        .documentationReference("docs/rules/" + id + ".md")
        .build();
  }

  private static Violation errorViolation() {
    return Violation.builder()
        .rule(rule("WG001"))
        .file(Path.of("Foo.java"))
        .className("Foo")
        .line(1)
        .message("m")
        .suggestedFix("f")
        .build();
  }

  private static ValidationSummary.Builder summaryBuilder() {
    return ValidationSummary.builder()
        .projectName("sample-project")
        .timestamp(Instant.now())
        .woguVersion("0.1.0")
        .javaVersion("17")
        .buildTool("Maven");
  }

  @Test
  void hasNoBuildFailuresWhenAllRulesPass() {
    RuleResult passing = RuleResult.of(rule("WG001"), List.of(), Duration.ofMillis(2));
    ValidationSummary summary =
        summaryBuilder().results(List.of(passing)).totalExecutionTime(Duration.ofMillis(2)).build();

    assertThat(summary.hasBuildFailures()).isFalse();
    assertThat(summary.allViolations()).isEmpty();
  }

  @Test
  void hasBuildFailuresWhenAnyRuleFails() {
    RuleResult failing = RuleResult.of(rule("WG001"), List.of(errorViolation()), Duration.ofMillis(3));
    RuleResult passing = RuleResult.of(rule("WG002"), List.of(), Duration.ofMillis(1));
    ValidationSummary summary =
        summaryBuilder().results(List.of(failing, passing)).totalExecutionTime(Duration.ofMillis(4)).build();

    assertThat(summary.hasBuildFailures()).isTrue();
    assertThat(summary.allViolations()).hasSize(1);
  }

  @Test
  void resultsListIsImmutable() {
    ValidationSummary summary = summaryBuilder().results(List.of()).totalExecutionTime(Duration.ZERO).build();

    assertThat(summary.results()).isUnmodifiable();
  }

  @Test
  void exposesBuildMetadata() {
    ValidationSummary summary = summaryBuilder()
        .results(List.of())
        .totalExecutionTime(Duration.ZERO)
        .scannedElementCount(4)
        .build();

    assertThat(summary.woguVersion()).isEqualTo("0.1.0");
    assertThat(summary.javaVersion()).isEqualTo("17");
    assertThat(summary.buildTool()).isEqualTo("Maven");
    assertThat(summary.scannedElementCount()).isEqualTo(4);
  }
}
