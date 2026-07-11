package dev.wogu.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wogu.api.CallPathFrame;
import dev.wogu.api.Rule;
import dev.wogu.api.RuleCategory;
import dev.wogu.api.RuleResult;
import dev.wogu.api.Severity;
import dev.wogu.api.ValidationSummary;
import dev.wogu.api.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportGeneratorTest {

  @TempDir Path outputDirectory;

  private final HtmlReportGenerator generator = new HtmlReportGenerator();

  private static Rule.Builder ruleBuilder() {
    return Rule.builder()
        .id("WG001")
        .title("UUID.randomUUID() inside Workflow")
        .category(RuleCategory.DETERMINISM)
        .severity(Severity.ERROR)
        .engine("Temporal Java SDK")
        .sinceVersion("0.1.0")
        .documentationReference("docs/rules/WG001.md");
  }

  private static ValidationSummary.Builder summaryBuilder() {
    return ValidationSummary.builder()
        .projectName("sample-project")
        .timestamp(Instant.parse("2026-01-15T10:30:00Z"))
        .woguVersion("0.1.0")
        .javaVersion("17.0.13")
        .buildTool("Maven");
  }

  @Test
  void writesIndexHtmlUnderTheOutputDirectory() throws IOException {
    ValidationSummary summary = summaryBuilder().results(List.of()).totalExecutionTime(Duration.ZERO).build();

    Path reportFile = generator.generate(summary, outputDirectory);

    assertThat(reportFile).exists().hasFileName("index.html");
    assertThat(outputDirectory.resolve("index.html")).exists();
  }

  @Test
  void createsMissingOutputDirectories() throws IOException {
    Path nested = outputDirectory.resolve("nested/deeper");
    ValidationSummary summary = summaryBuilder().results(List.of()).totalExecutionTime(Duration.ZERO).build();

    Path reportFile = generator.generate(summary, nested);

    assertThat(reportFile).exists();
  }

  @Test
  void includesBuildInformationAndPassedStatusWhenThereAreNoViolations() throws IOException {
    RuleResult passing = RuleResult.of(ruleBuilder().build(), List.of(), Duration.ofMillis(12));
    ValidationSummary summary =
        summaryBuilder().results(List.of(passing)).totalExecutionTime(Duration.ofMillis(12)).build();

    Path reportFile = generator.generate(summary, outputDirectory);
    String html = Files.readString(reportFile);

    assertThat(html).contains("sample-project");
    assertThat(html).contains("2026-01-15 10:30:00 UTC");
    assertThat(html).contains("Maven");
    assertThat(html).contains("17.0.13");
    assertThat(html).contains("PASSED");
    assertThat(html).contains("WG001");
    assertThat(html).contains("UUID.randomUUID() inside Workflow");
    assertThat(html).contains("DETERMINISM");
    assertThat(html).contains("No violations found.");
    assertThat(html).doesNotContain("<script");
  }

  @Test
  void includesViolationDetailsCallPathAndFailedStatusWhenThereAreViolations() throws IOException {
    Rule rule = ruleBuilder().build();
    Violation violation =
        Violation.builder()
            .rule(rule)
            .file(Path.of("src/main/java/com/example/PaymentWorkflowImpl.java"))
            .className("com.example.PaymentWorkflowImpl")
            .line(42)
            .message("UUID.randomUUID() generates a different value every execution.")
            .suggestedFix("Use Workflow.randomUUID() instead")
            .callPath(
                List.of(
                    new CallPathFrame(
                        "PaymentWorkflowImpl.processPayment()",
                        Path.of("src/main/java/com/example/PaymentWorkflowImpl.java"),
                        10),
                    new CallPathFrame(
                        "UUID.randomUUID()", Path.of("src/main/java/com/example/PaymentWorkflowImpl.java"), 12)))
            .build();
    RuleResult failing = RuleResult.of(rule, List.of(violation), Duration.ofMillis(8));
    ValidationSummary summary =
        summaryBuilder().results(List.of(failing)).totalExecutionTime(Duration.ofMillis(8)).build();

    Path reportFile = generator.generate(summary, outputDirectory);
    String html = Files.readString(reportFile);

    assertThat(html).contains("FAILED");
    assertThat(html).contains("PaymentWorkflowImpl.java");
    assertThat(html).contains("com.example.PaymentWorkflowImpl");
    assertThat(html).contains("42");
    assertThat(html).contains("UUID.randomUUID() generates a different value every execution.");
    assertThat(html).contains("Use Workflow.randomUUID() instead");
    assertThat(html).contains("ERROR");
    assertThat(html).contains("PaymentWorkflowImpl.processPayment()");
    assertThat(html).contains("call-path-arrow");
  }

  @Test
  void rendersRuleDocumentationAsALinkOnlyWhenItIsARealUrl() throws IOException {
    Rule relativeDocRule = ruleBuilder().build();
    Rule hostedDocRule =
        Rule.builder()
            .id("WG002")
            .title("Another rule")
            .category(RuleCategory.DETERMINISM)
            .severity(Severity.WARNING)
            .engine("Temporal Java SDK")
            .sinceVersion("0.2.0")
            .documentationReference("https://wogu.dev/rules/WG002")
            .build();
    RuleResult result1 = RuleResult.of(relativeDocRule, List.of(), Duration.ofMillis(1));
    RuleResult result2 = RuleResult.of(hostedDocRule, List.of(), Duration.ofMillis(1));
    ValidationSummary summary =
        summaryBuilder().results(List.of(result1, result2)).totalExecutionTime(Duration.ofMillis(2)).build();

    Path reportFile = generator.generate(summary, outputDirectory);
    String html = Files.readString(reportFile);

    assertThat(html).doesNotContain("<a href=\"docs/rules/WG001.md\"");
    assertThat(html).contains("<a href=\"https://wogu.dev/rules/WG002\">");
  }

  @Test
  void escapesUserSuppliedContentInViolations() throws IOException {
    Rule rule =
        Rule.builder()
            .id("WG001")
            .title("t")
            .category(RuleCategory.DETERMINISM)
            .severity(Severity.WARNING)
            .engine("Temporal Java SDK")
            .sinceVersion("0.1.0")
            .documentationReference("docs/rules/WG001.md")
            .build();
    Violation violation =
        Violation.builder()
            .rule(rule)
            .file(Path.of("<Foo>.java"))
            .className("Foo<T>")
            .line(1)
            .message("m & <script>alert(1)</script>")
            .suggestedFix("f")
            .build();
    RuleResult result = RuleResult.of(rule, List.of(violation), Duration.ofMillis(1));
    ValidationSummary summary =
        summaryBuilder().results(List.of(result)).totalExecutionTime(Duration.ofMillis(1)).build();

    Path reportFile = generator.generate(summary, outputDirectory);
    String html = Files.readString(reportFile);

    assertThat(html).doesNotContain("<script>alert(1)</script>");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
  }
}
