package io.wogu.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The aggregate outcome of a single WoGu run: every {@link ValidationResult} produced by
 * every executed {@link WorkflowValidator}, plus the metadata a report renderer needs
 * (project name, when the run happened, total duration).
 *
 * <p>This type is the hand-off point between the engine (which produces it) and both the
 * HTML report generator and the build-tool plugins (which consume it to decide whether to
 * fail the build). It lives in {@code wogu-api}, not {@code wogu-core}, so that
 * {@code wogu-report} depends only on the API module, not on the engine implementation.
 */
public final class ValidationSummary {

  private final String projectName;
  private final Instant timestamp;
  private final List<ValidationResult> results;
  private final Duration totalExecutionTime;

  private ValidationSummary(
      String projectName, Instant timestamp, List<ValidationResult> results, Duration totalExecutionTime) {
    this.projectName = Objects.requireNonNull(projectName, "projectName");
    this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    this.results = List.copyOf(Objects.requireNonNull(results, "results"));
    this.totalExecutionTime = Objects.requireNonNull(totalExecutionTime, "totalExecutionTime");
  }

  /**
   * Creates a summary for a completed engine run.
   *
   * @param projectName human-readable name of the project that was validated
   * @param timestamp when the run completed
   * @param results one {@link ValidationResult} per executed validator
   * @param totalExecutionTime total wall-clock time for the whole run
   */
  public static ValidationSummary of(
      String projectName, Instant timestamp, List<ValidationResult> results, Duration totalExecutionTime) {
    return new ValidationSummary(projectName, timestamp, results, totalExecutionTime);
  }

  /** Name of the project that was validated. */
  public String projectName() {
    return projectName;
  }

  /** When this run completed. */
  public Instant timestamp() {
    return timestamp;
  }

  /** One result per validator that was executed, in execution order. */
  public List<ValidationResult> results() {
    return results;
  }

  /** Total wall-clock time for the whole engine run. */
  public Duration totalExecutionTime() {
    return totalExecutionTime;
  }

  /** All violations from all validators, flattened into a single list. */
  public List<Violation> allViolations() {
    return results.stream().flatMap(r -> r.violations().stream()).toList();
  }

  /**
   * Whether any executed validator produced a build-blocking violation.
   *
   * @return {@code true} if the build tool integration should fail the build
   */
  public boolean hasBuildFailures() {
    return results.stream().anyMatch(r -> !r.passed());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ValidationSummary other)) {
      return false;
    }
    return projectName.equals(other.projectName)
        && timestamp.equals(other.timestamp)
        && results.equals(other.results)
        && totalExecutionTime.equals(other.totalExecutionTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectName, timestamp, results, totalExecutionTime);
  }

  @Override
  public String toString() {
    return "ValidationSummary{projectName=%s, validators=%d, violations=%d, hasBuildFailures=%s}"
        .formatted(projectName, results.size(), allViolations().size(), hasBuildFailures());
  }
}
