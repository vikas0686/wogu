package io.wogu.report;

import io.wogu.api.ValidationResult;
import io.wogu.api.ValidationSummary;
import io.wogu.api.Violation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link ValidationSummary} as a single, self-contained {@code index.html} file:
 * plain HTML and inline CSS, no JavaScript, no external resources — the report opens
 * correctly straight from disk, offline, in any browser.
 *
 * <p>Mirrors the shape of a JaCoCo coverage report: an overview of the run, a
 * per-validator summary, and a detailed table of every violation found.
 */
public final class HtmlReportGenerator {

  private static final String REPORT_FILE_NAME = "index.html";
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

  /**
   * Writes {@code summary} to {@code outputDirectory}{@code /index.html}, creating the
   * directory (and any missing parents) if necessary.
   *
   * @param summary the validation run to render
   * @param outputDirectory directory the report is written into, e.g.
   *     {@code target/wogu} or {@code build/reports/wogu}
   * @return the path of the written {@code index.html}
   * @throws IOException if the report file could not be written
   */
  public Path generate(ValidationSummary summary, Path outputDirectory) throws IOException {
    Files.createDirectories(outputDirectory);
    Path reportFile = outputDirectory.resolve(REPORT_FILE_NAME);
    Files.writeString(reportFile, render(summary), StandardCharsets.UTF_8);
    return reportFile;
  }

  private String render(ValidationSummary summary) {
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html>\n<html lang=\"en\">\n");
    html.append(renderHead(summary));
    html.append("<body>\n");
    html.append(renderHeader(summary));
    html.append("<main>\n");
    html.append(renderOverview(summary));
    html.append(renderValidatorSummary(summary));
    html.append(renderViolations(summary));
    html.append("</main>\n");
    html.append(renderFooter());
    html.append("</body>\n</html>\n");
    return html.toString();
  }

  private String renderHead(ValidationSummary summary) {
    return "<head>\n"
        + "<meta charset=\"UTF-8\">\n"
        + "<title>WoGu Report - "
        + HtmlEscaper.escape(summary.projectName())
        + "</title>\n"
        + "<style>\n"
        + CSS
        + "</style>\n"
        + "</head>\n";
  }

  private String renderHeader(ValidationSummary summary) {
    String statusClass = summary.hasBuildFailures() ? "status-failed" : "status-passed";
    String statusLabel = summary.hasBuildFailures() ? "FAILED" : "PASSED";
    return "<header>\n"
        + "<div class=\"header-inner\">\n"
        + "<h1>WoGu <span class=\"subtitle\">Workflow Guard</span></h1>\n"
        + "<span class=\"badge "
        + statusClass
        + "\">"
        + statusLabel
        + "</span>\n"
        + "</div>\n"
        + "</header>\n";
  }

  private String renderOverview(ValidationSummary summary) {
    StringBuilder section = new StringBuilder();
    section.append("<section class=\"overview\">\n<h2>Overview</h2>\n<div class=\"cards\">\n");
    section.append(card("Project", HtmlEscaper.escape(summary.projectName())));
    section.append(card("Build Timestamp", TIMESTAMP_FORMAT.format(summary.timestamp())));
    section.append(card("Validators Run", String.valueOf(summary.results().size())));
    section.append(card("Total Violations", String.valueOf(summary.allViolations().size())));
    section.append(card("Execution Time", formatDuration(summary.totalExecutionTime())));
    section.append("</div>\n</section>\n");
    return section.toString();
  }

  private String card(String label, String value) {
    return "<div class=\"card\"><div class=\"card-label\">" + label + "</div><div class=\"card-value\">" + value
        + "</div></div>\n";
  }

  private String renderValidatorSummary(ValidationSummary summary) {
    StringBuilder section = new StringBuilder();
    section.append("<section class=\"validators\">\n<h2>Validator Summary</h2>\n");
    section.append("<table>\n<thead><tr><th>Validator</th><th>Status</th><th>Violations</th>"
        + "<th>Execution Time</th></tr></thead>\n<tbody>\n");
    for (ValidationResult result : summary.results()) {
      String statusClass = result.passed() ? "status-passed" : "status-failed";
      String statusLabel = result.passed() ? "PASSED" : "FAILED";
      section
          .append("<tr><td><code>")
          .append(HtmlEscaper.escape(result.validatorId()))
          .append("</code></td><td><span class=\"badge ")
          .append(statusClass)
          .append("\">")
          .append(statusLabel)
          .append("</span></td><td>")
          .append(result.violations().size())
          .append("</td><td>")
          .append(formatDuration(result.executionTime()))
          .append("</td></tr>\n");
    }
    if (summary.results().isEmpty()) {
      section.append("<tr><td colspan=\"4\" class=\"empty\">No validators were executed.</td></tr>\n");
    }
    section.append("</tbody>\n</table>\n</section>\n");
    return section.toString();
  }

  private String renderViolations(ValidationSummary summary) {
    List<Violation> violations = summary.allViolations();
    StringBuilder section = new StringBuilder();
    section.append("<section class=\"violations\">\n<h2>Violations</h2>\n");
    if (violations.isEmpty()) {
      section.append("<p class=\"empty\">No violations found.</p>\n");
    } else {
      section.append("<table>\n<thead><tr><th>Severity</th><th>File</th><th>Class</th><th>Line</th>"
          + "<th>Message</th><th>Suggested Fix</th></tr></thead>\n<tbody>\n");
      for (Violation violation : violations) {
        section
            .append("<tr><td><span class=\"badge severity-")
            .append(violation.severity().name().toLowerCase(Locale.ROOT))
            .append("\">")
            .append(violation.severity())
            .append("</span></td><td><code>")
            .append(HtmlEscaper.escape(violation.file().toString()))
            .append("</code></td><td><code>")
            .append(HtmlEscaper.escape(violation.className()))
            .append("</code></td><td>")
            .append(violation.line())
            .append("</td><td>")
            .append(HtmlEscaper.escape(violation.message()))
            .append("</td><td>")
            .append(HtmlEscaper.escape(violation.suggestedFix()))
            .append("</td></tr>\n");
      }
      section.append("</tbody>\n</table>\n");
    }
    section.append("</section>\n");
    return section.toString();
  }

  private String renderFooter() {
    String version = HtmlReportGenerator.class.getPackage().getImplementationVersion();
    return "<footer>Generated by WoGu " + (version == null ? "(development)" : "v" + version) + "</footer>\n";
  }

  private static String formatDuration(Duration duration) {
    long millis = duration.toMillis();
    if (millis < 1000) {
      return millis + " ms";
    }
    return String.format(Locale.ROOT, "%.2f s", millis / 1000.0);
  }

  private static final String CSS =
      """
      :root {
        color-scheme: light dark;
        --bg: #f7f8fa;
        --surface: #ffffff;
        --text: #1c1e21;
        --muted: #5f6672;
        --border: #e2e5ea;
        --accent: #3457d5;
        --pass-bg: #e6f4ea;
        --pass-fg: #1e7e34;
        --fail-bg: #fdecea;
        --fail-fg: #b3261e;
        --warn-bg: #fff4e5;
        --warn-fg: #9a6700;
        --info-bg: #e8f0fe;
        --info-fg: #1a56b0;
      }
      @media (prefers-color-scheme: dark) {
        :root {
          --bg: #14161a;
          --surface: #1d2026;
          --text: #e7e9ec;
          --muted: #9aa1ac;
          --border: #2c303a;
          --accent: #7f9cff;
          --pass-bg: #123822;
          --pass-fg: #6fd992;
          --fail-bg: #3a1414;
          --fail-fg: #ff8f87;
          --warn-bg: #3a2c0d;
          --warn-fg: #f5c451;
          --info-bg: #12233f;
          --info-fg: #8fb8ff;
        }
      }
      * { box-sizing: border-box; }
      body {
        margin: 0;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        background: var(--bg);
        color: var(--text);
      }
      header {
        background: var(--surface);
        border-bottom: 1px solid var(--border);
      }
      .header-inner {
        max-width: 1100px;
        margin: 0 auto;
        padding: 20px 24px;
        display: flex;
        align-items: center;
        justify-content: space-between;
      }
      h1 { margin: 0; font-size: 22px; font-weight: 700; }
      h1 .subtitle { font-weight: 400; color: var(--muted); font-size: 16px; margin-left: 8px; }
      main { max-width: 1100px; margin: 0 auto; padding: 24px; }
      section { margin-bottom: 32px; }
      h2 { font-size: 15px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--muted); margin-bottom: 12px; }
      .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
      .card {
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 8px;
        padding: 16px;
      }
      .card-label { font-size: 12px; color: var(--muted); margin-bottom: 6px; }
      .card-value { font-size: 20px; font-weight: 600; word-break: break-word; }
      table { width: 100%; border-collapse: collapse; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
      th, td { text-align: left; padding: 10px 14px; border-bottom: 1px solid var(--border); font-size: 14px; }
      th { color: var(--muted); font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.03em; }
      tr:last-child td { border-bottom: none; }
      td.empty { color: var(--muted); font-style: italic; }
      p.empty { color: var(--muted); font-style: italic; }
      code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
      .badge { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; }
      .status-passed { background: var(--pass-bg); color: var(--pass-fg); }
      .status-failed { background: var(--fail-bg); color: var(--fail-fg); }
      .severity-error { background: var(--fail-bg); color: var(--fail-fg); }
      .severity-warning { background: var(--warn-bg); color: var(--warn-fg); }
      .severity-info { background: var(--info-bg); color: var(--info-fg); }
      footer { text-align: center; padding: 24px; color: var(--muted); font-size: 12px; }
      """;
}
