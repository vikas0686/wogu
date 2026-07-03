package io.wogu.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.wogu.api.Rule;
import io.wogu.api.RuleCategory;
import io.wogu.api.RuleResult;
import io.wogu.api.Severity;
import io.wogu.api.ValidationSummary;
import io.wogu.api.Violation;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsoleReportRendererTest {

  private static Rule rule(String id, String title, Severity severity) {
    return Rule.builder()
        .id(id)
        .title(title)
        .category(RuleCategory.DETERMINISM)
        .severity(severity)
        .engine("Temporal Java SDK")
        .sinceVersion("0.1.0")
        .documentationReference("docs/rules/" + id + ".md")
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
  void rendersAPassingRunWithNoViolations() {
    Rule wg002 = rule("WG002", "Some other rule", Severity.ERROR);
    RuleResult passing = RuleResult.of(wg002, List.of(), Duration.ofMillis(5));
    ValidationSummary summary =
        summaryBuilder().results(List.of(passing)).totalExecutionTime(Duration.ofMillis(5)).scannedElementCount(4).build();

    List<String> lines = ConsoleReportRenderer.render(summary, Path.of("target/wogu/index.html"));
    String output = String.join("\n", lines);

    assertThat(output).contains("WoGu Workflow Guard");
    assertThat(output).contains("Found 4 workflow classes");
    assertThat(output).contains("✓ WG002");
    assertThat(output).contains("0 violations");
    assertThat(output).contains("Build PASSED");
    assertThat(output).contains("target/wogu/index.html");
  }

  @Test
  void rendersAFailingRunWithTheViolationCountAndFailingRuleTitle() {
    Rule wg001 = rule("WG001", "UUID.randomUUID() inside Workflow", Severity.ERROR);
    Violation violation =
        Violation.builder()
            .rule(wg001)
            .file(Path.of("Foo.java"))
            .className("Foo")
            .line(1)
            .message("m")
            .suggestedFix("f")
            .build();
    RuleResult failing = RuleResult.of(wg001, List.of(violation), Duration.ofMillis(3));
    ValidationSummary summary =
        summaryBuilder().results(List.of(failing)).totalExecutionTime(Duration.ofMillis(3)).scannedElementCount(1).build();

    List<String> lines = ConsoleReportRenderer.render(summary, Path.of("target/wogu/index.html"));
    String output = String.join("\n", lines);

    assertThat(output).contains("Found 1 workflow class");
    assertThat(output).contains("✗ WG001 UUID.randomUUID() inside Workflow");
    assertThat(output).contains("1 ERROR");
    assertThat(output).contains("Build FAILED");
  }

  @Test
  void groupsViolationCountsBySeverityInErrorWarningInfoOrder() {
    Rule wg001 = rule("WG001", "t1", Severity.ERROR);
    Rule wg002 = rule("WG002", "t2", Severity.WARNING);
    Violation error =
        Violation.builder().rule(wg001).file(Path.of("Foo.java")).className("Foo").line(1).message("m").suggestedFix("f").build();
    Violation warning =
        Violation.builder().rule(wg002).file(Path.of("Foo.java")).className("Foo").line(2).message("m").suggestedFix("f").build();
    RuleResult r1 = RuleResult.of(wg001, List.of(error), Duration.ofMillis(1));
    RuleResult r2 = RuleResult.of(wg002, List.of(warning), Duration.ofMillis(1));
    ValidationSummary summary =
        summaryBuilder().results(List.of(r1, r2)).totalExecutionTime(Duration.ofMillis(2)).scannedElementCount(1).build();

    List<String> lines = ConsoleReportRenderer.render(summary, Path.of("target/wogu/index.html"));

    assertThat(lines).anySatisfy(line -> assertThat(line).contains("1 ERROR").contains("1 WARNING"));
  }
}
