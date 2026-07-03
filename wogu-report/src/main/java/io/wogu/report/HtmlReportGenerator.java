package io.wogu.report;

import io.wogu.api.CallPathFrame;
import io.wogu.api.Rule;
import io.wogu.api.RuleResult;
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
 * <p>Mirrors the shape of a JaCoCo coverage report: build information, a per-rule summary,
 * and a detailed view of every violation found, including its full call path when the
 * rule that found it performed call-graph analysis.
 *
 * <p>Rendering only ever reads {@link Rule} metadata and {@link Violation} data — it has
 * no per-rule-id logic, so adding a new rule anywhere (this engine or a future one) never
 * requires a change here.
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
    html.append(renderBuildInformation(summary));
    html.append(renderRuleSummary(summary));
    html.append(renderViolations(summary));
    html.append("</main>\n");
    html.append(renderFooter(summary));
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

  private String renderBuildInformation(ValidationSummary summary) {
    StringBuilder section = new StringBuilder();
    section.append("<section class=\"overview\">\n<h2>Build Information</h2>\n<div class=\"cards\">\n");
    section.append(card("Project", HtmlEscaper.escape(summary.projectName())));
    section.append(card("Build Tool", HtmlEscaper.escape(summary.buildTool())));
    section.append(card("WoGu Version", HtmlEscaper.escape(summary.woguVersion())));
    section.append(card("Java Version", HtmlEscaper.escape(summary.javaVersion())));
    section.append(card("Build Timestamp", TIMESTAMP_FORMAT.format(summary.timestamp())));
    section.append(card("Execution Time", formatDuration(summary.totalExecutionTime())));
    section.append(card("Rules Executed", String.valueOf(summary.results().size())));
    section.append(card("Total Violations", String.valueOf(summary.allViolations().size())));
    section.append("</div>\n</section>\n");
    return section.toString();
  }

  private String card(String label, String value) {
    return "<div class=\"card\"><div class=\"card-label\">" + label + "</div><div class=\"card-value\">" + value
        + "</div></div>\n";
  }

  private String renderRuleSummary(ValidationSummary summary) {
    StringBuilder section = new StringBuilder();
    section.append("<section class=\"rules\">\n<h2>Rule Summary</h2>\n<div class=\"table-scroll\">\n");
    section.append(
        "<table>\n<thead><tr><th>Rule ID</th><th>Title</th><th>Category</th><th>Severity</th>"
            + "<th>Status</th><th>Violations</th><th>Execution Time</th></tr></thead>\n<tbody>\n");
    for (RuleResult result : summary.results()) {
      Rule rule = result.rule();
      String statusClass = result.passed() ? "status-passed" : "status-failed";
      String statusLabel = result.passed() ? "PASSED" : "FAILED";
      section
          .append("<tr><td>")
          .append(ruleIdLink(rule))
          .append("</td><td>")
          .append(HtmlEscaper.escape(rule.title()))
          .append("</td><td>")
          .append(HtmlEscaper.escape(rule.category().name()))
          .append("</td><td><span class=\"badge severity-")
          .append(rule.severity().name().toLowerCase(Locale.ROOT))
          .append("\">")
          .append(rule.severity())
          .append("</span></td><td><span class=\"badge ")
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
      section.append("<tr><td colspan=\"7\" class=\"empty\">No rules were executed.</td></tr>\n");
    }
    section.append("</tbody>\n</table>\n</div>\n</section>\n");
    return section.toString();
  }

  /**
   * Renders a rule's id as a link to its documentation when that documentation is a real
   * URL, or as plain text otherwise (e.g. a repo-relative path like
   * {@code docs/rules/WG001.md}, which isn't reliably a working link from the report's own
   * location). Adding hosted rule docs later needs no change here: any {@code http(s)://}
   * reference automatically becomes a link.
   */
  private String ruleIdLink(Rule rule) {
    String reference = rule.documentationReference();
    String id = HtmlEscaper.escape(rule.id());
    if (reference.startsWith("http://") || reference.startsWith("https://")) {
      return "<a href=\"" + HtmlEscaper.escape(reference) + "\"><code>" + id + "</code></a>";
    }
    return "<code>" + id + "</code>";
  }

  private String renderViolations(ValidationSummary summary) {
    List<Violation> violations = summary.allViolations();
    StringBuilder section = new StringBuilder();
    section.append("<section class=\"violations\">\n<h2>Violations</h2>\n");
    if (violations.isEmpty()) {
      section.append("<p class=\"empty\">No violations found.</p>\n");
    } else {
      for (Violation violation : violations) {
        section.append(renderViolationCard(violation));
      }
    }
    section.append("</section>\n");
    return section.toString();
  }

  private String renderViolationCard(Violation violation) {
    Rule rule = violation.rule();
    StringBuilder card = new StringBuilder();
    card.append("<div class=\"violation-card\">\n");
    card.append("<div class=\"violation-header\">")
        .append("<span class=\"badge severity-")
        .append(rule.severity().name().toLowerCase(Locale.ROOT))
        .append("\">")
        .append(rule.severity())
        .append("</span>")
        .append("<code class=\"violation-rule-id\">")
        .append(HtmlEscaper.escape(rule.id()))
        .append("</code>")
        .append("<span class=\"violation-rule-title\">")
        .append(HtmlEscaper.escape(rule.title()))
        .append("</span>")
        .append("</div>\n");
    card.append("<div class=\"violation-location\"><code>")
        .append(HtmlEscaper.escape(violation.className()))
        .append("</code> &mdash; <code>")
        .append(HtmlEscaper.escape(violation.file().toString()))
        .append(":")
        .append(violation.line())
        .append("</code></div>\n");
    if (!violation.callPath().isEmpty()) {
      card.append(renderCallPath(violation.callPath()));
    }
    card.append("<p class=\"violation-message\">").append(HtmlEscaper.escape(violation.message())).append("</p>\n");
    card.append("<p class=\"violation-fix\"><strong>Recommended Fix:</strong> ")
        .append(HtmlEscaper.escape(violation.suggestedFix()))
        .append("</p>\n");
    card.append("</div>\n");
    return card.toString();
  }

  private String renderCallPath(List<CallPathFrame> callPath) {
    StringBuilder path = new StringBuilder();
    path.append("<div class=\"call-path\">\n");
    for (int i = 0; i < callPath.size(); i++) {
      if (i > 0) {
        path.append("<div class=\"call-path-arrow\">↓</div>\n");
      }
      boolean isLast = i == callPath.size() - 1;
      path.append("<div class=\"call-path-frame")
          .append(isLast ? " call-path-final" : "")
          .append("\"><code>")
          .append(HtmlEscaper.escape(callPath.get(i).displayName()))
          .append("</code></div>\n");
    }
    path.append("</div>\n");
    return path.toString();
  }

  private String renderFooter(ValidationSummary summary) {
    return "<footer>Generated by WoGu v" + HtmlEscaper.escape(summary.woguVersion()) + "</footer>\n";
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
      .table-scroll { overflow-x: auto; overflow-y: hidden; border: 1px solid var(--border); border-radius: 8px; }
      table { width: 100%; min-width: 640px; border-collapse: collapse; background: var(--surface); }
      th, td { text-align: left; padding: 10px 14px; border-bottom: 1px solid var(--border); font-size: 14px; }
      td { max-width: 320px; overflow-wrap: anywhere; }
      th { color: var(--muted); font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.03em; }
      tr:last-child td { border-bottom: none; }
      td.empty { color: var(--muted); font-style: italic; }
      p.empty { color: var(--muted); font-style: italic; }
      a { color: var(--accent); }
      code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
      .badge { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
      .status-passed { background: var(--pass-bg); color: var(--pass-fg); }
      .status-failed { background: var(--fail-bg); color: var(--fail-fg); }
      .severity-error { background: var(--fail-bg); color: var(--fail-fg); }
      .severity-warning { background: var(--warn-bg); color: var(--warn-fg); }
      .severity-info { background: var(--info-bg); color: var(--info-fg); }
      .violation-card {
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 8px;
        padding: 16px 20px;
        margin-bottom: 12px;
      }
      .violation-header { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
      .violation-rule-id { font-weight: 600; }
      .violation-rule-title { color: var(--muted); font-size: 14px; }
      .violation-location { margin-bottom: 12px; color: var(--muted); }
      .call-path { margin: 12px 0; padding: 12px 16px; background: var(--bg); border-radius: 6px; }
      .call-path-frame code { font-size: 13px; }
      .call-path-final code { font-weight: 600; color: var(--fail-fg); }
      .call-path-arrow { color: var(--muted); padding-left: 4px; line-height: 1.4; }
      .violation-message { margin: 12px 0; line-height: 1.5; }
      .violation-fix { margin: 8px 0 0; line-height: 1.5; }
      footer { text-align: center; padding: 24px; color: var(--muted); font-size: 12px; }
      """;
}
