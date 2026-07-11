package dev.wogu.core;

import dev.wogu.api.RuleResult;
import dev.wogu.api.Severity;
import dev.wogu.api.ValidationSummary;
import dev.wogu.api.Violation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders a {@link ValidationSummary} as human-readable console output, shared by the
 * Maven and Gradle plugins so a build looks the same regardless of which one ran it.
 *
 * <p>Returns plain lines rather than printing directly, since each build tool logs
 * through its own API ({@code Log}/{@code Logger}) rather than {@code System.out}.
 */
public final class ConsoleReportRenderer {

  private static final String SEPARATOR = "----------------------------------------------------";
  private static final String PASS_MARK = "✓"; // ✓
  private static final String FAIL_MARK = "✗"; // ✗

  private ConsoleReportRenderer() {}

  /**
   * Renders the full run: banner, scan summary, per-rule pass/fail, violation counts by
   * severity, build status, and where the HTML report was written.
   *
   * @param summary the completed engine run
   * @param reportPath where the HTML report was written
   */
  public static List<String> render(ValidationSummary summary, Path reportPath) {
    List<String> lines = new ArrayList<>();
    lines.add(SEPARATOR);
    lines.add("WoGu Workflow Guard");
    lines.add(SEPARATOR);
    lines.add("Scanning project...");
    lines.add("");
    lines.add(PASS_MARK + " Found " + summary.scannedElementCount() + " workflow "
        + (summary.scannedElementCount() == 1 ? "class" : "classes"));
    lines.add("");
    lines.add("Running Rules...");
    lines.add("");
    for (RuleResult result : summary.results()) {
      if (result.passed()) {
        lines.add(PASS_MARK + " " + result.rule().id());
      } else {
        lines.add(FAIL_MARK + " " + result.rule().id() + " " + result.rule().title());
      }
    }
    lines.add("----------------------------------------");
    lines.add(violationCountsBySeverity(summary));
    lines.add(summary.hasBuildFailures() ? "Build FAILED" : "Build PASSED");
    lines.add("");
    lines.add("HTML Report");
    lines.add(reportPath.toString());
    return lines;
  }

  private static String violationCountsBySeverity(ValidationSummary summary) {
    Map<Severity, Long> counts =
        summary.allViolations().stream()
            .collect(Collectors.groupingBy(Violation::severity, LinkedHashMap::new, Collectors.counting()));
    if (counts.isEmpty()) {
      return "0 violations";
    }
    return List.of(Severity.ERROR, Severity.WARNING, Severity.INFO).stream()
        .filter(counts::containsKey)
        .map(severity -> counts.get(severity) + " " + severity)
        .collect(Collectors.joining(", "));
  }
}
