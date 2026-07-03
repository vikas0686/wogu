package io.wogu.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.wogu.api.Severity;
import io.wogu.api.ValidationResult;
import io.wogu.api.ValidationSummary;
import io.wogu.api.Violation;
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

  @Test
  void writesIndexHtmlUnderTheOutputDirectory() throws IOException {
    ValidationSummary summary =
        ValidationSummary.of("sample-project", Instant.parse("2026-01-15T10:30:00Z"), List.of(), Duration.ZERO);

    Path reportFile = generator.generate(summary, outputDirectory);

    assertThat(reportFile).exists().hasFileName("index.html");
    assertThat(outputDirectory.resolve("index.html")).exists();
  }

  @Test
  void createsMissingOutputDirectories() throws IOException {
    Path nested = outputDirectory.resolve("nested/deeper");
    ValidationSummary summary = ValidationSummary.of("p", Instant.now(), List.of(), Duration.ZERO);

    Path reportFile = generator.generate(summary, nested);

    assertThat(reportFile).exists();
  }

  @Test
  void includesProjectNameTimestampAndPassedStatusWhenThereAreNoViolations() throws IOException {
    ValidationResult passing = ValidationResult.of("uuid-random-in-workflow", List.of(), Duration.ofMillis(12));
    ValidationSummary summary =
        ValidationSummary.of(
            "sample-project", Instant.parse("2026-01-15T10:30:00Z"), List.of(passing), Duration.ofMillis(12));

    Path reportFile = generator.generate(summary, outputDirectory);
    String html = Files.readString(reportFile);

    assertThat(html).contains("sample-project");
    assertThat(html).contains("2026-01-15 10:30:00 UTC");
    assertThat(html).contains("PASSED");
    assertThat(html).contains("uuid-random-in-workflow");
    assertThat(html).contains("No violations found.");
    assertThat(html).doesNotContain("<script");
  }

  @Test
  void includesViolationDetailsAndFailedStatusWhenThereAreViolations() throws IOException {
    Violation violation =
        Violation.builder()
            .validatorId("uuid-random-in-workflow")
            .severity(Severity.ERROR)
            .file(Path.of("src/main/java/com/example/PaymentWorkflowImpl.java"))
            .className("com.example.PaymentWorkflowImpl")
            .line(42)
            .message("UUID.randomUUID() is non-deterministic")
            .suggestedFix("Use Workflow.randomUUID() instead")
            .build();
    ValidationResult failing =
        ValidationResult.of("uuid-random-in-workflow", List.of(violation), Duration.ofMillis(8));
    ValidationSummary summary =
        ValidationSummary.of("sample-project", Instant.now(), List.of(failing), Duration.ofMillis(8));

    Path reportFile = generator.generate(summary, outputDirectory);
    String html = Files.readString(reportFile);

    assertThat(html).contains("FAILED");
    assertThat(html).contains("PaymentWorkflowImpl.java");
    assertThat(html).contains("com.example.PaymentWorkflowImpl");
    assertThat(html).contains("42");
    assertThat(html).contains("UUID.randomUUID() is non-deterministic");
    assertThat(html).contains("Use Workflow.randomUUID() instead");
    assertThat(html).contains("ERROR");
  }

  @Test
  void escapesUserSuppliedContentInViolations() throws IOException {
    Violation violation =
        Violation.builder()
            .validatorId("v")
            .severity(Severity.WARNING)
            .file(Path.of("<Foo>.java"))
            .className("Foo<T>")
            .line(1)
            .message("m & <script>alert(1)</script>")
            .suggestedFix("f")
            .build();
    ValidationResult result = ValidationResult.of("v", List.of(violation), Duration.ofMillis(1));
    ValidationSummary summary = ValidationSummary.of("p", Instant.now(), List.of(result), Duration.ofMillis(1));

    Path reportFile = generator.generate(summary, outputDirectory);
    String html = Files.readString(reportFile);

    assertThat(html).doesNotContain("<script>alert(1)</script>");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
  }
}
