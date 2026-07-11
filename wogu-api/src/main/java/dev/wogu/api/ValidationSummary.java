package dev.wogu.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The aggregate outcome of a single WoGu run: every {@link RuleResult} produced by every
 * executed {@link WorkflowValidator}, plus the build/environment metadata a report
 * renderer needs.
 *
 * <p>This type is the hand-off point between the engine (which produces it) and both the
 * HTML report generator and the build-tool plugins (which consume it to decide whether to
 * fail the build). It lives in {@code wogu-api}, not {@code wogu-core}, so that
 * {@code wogu-report} depends only on the API module, not on the engine implementation.
 *
 * <p>Instances are immutable. Use {@link #builder()} to construct one.
 */
public final class ValidationSummary {

  private final String projectName;
  private final Instant timestamp;
  private final List<RuleResult> results;
  private final Duration totalExecutionTime;
  private final int scannedElementCount;
  private final String woguVersion;
  private final String javaVersion;
  private final String buildTool;

  private ValidationSummary(Builder builder) {
    this.projectName = Objects.requireNonNull(builder.projectName, "projectName");
    this.timestamp = Objects.requireNonNull(builder.timestamp, "timestamp");
    this.results = List.copyOf(Objects.requireNonNull(builder.results, "results"));
    this.totalExecutionTime = Objects.requireNonNull(builder.totalExecutionTime, "totalExecutionTime");
    this.woguVersion = Objects.requireNonNull(builder.woguVersion, "woguVersion");
    this.javaVersion = Objects.requireNonNull(builder.javaVersion, "javaVersion");
    this.buildTool = Objects.requireNonNull(builder.buildTool, "buildTool");
    if (builder.scannedElementCount < 0) {
      throw new IllegalArgumentException("scannedElementCount must be >= 0, was " + builder.scannedElementCount);
    }
    this.scannedElementCount = builder.scannedElementCount;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Name of the project that was validated. */
  public String projectName() {
    return projectName;
  }

  /** When this run completed. */
  public Instant timestamp() {
    return timestamp;
  }

  /** One result per rule that was evaluated, across every executed validator. */
  public List<RuleResult> results() {
    return results;
  }

  /** Total wall-clock time for the whole engine run. */
  public Duration totalExecutionTime() {
    return totalExecutionTime;
  }

  /** Total top-level units scanned across all validators (e.g. workflow classes), for diagnostics only. */
  public int scannedElementCount() {
    return scannedElementCount;
  }

  /** WoGu version that produced this summary. */
  public String woguVersion() {
    return woguVersion;
  }

  /** JVM version the build ran on. */
  public String javaVersion() {
    return javaVersion;
  }

  /** Build tool that drove this run, e.g. {@code "Maven"} or {@code "Gradle"}. */
  public String buildTool() {
    return buildTool;
  }

  /** All violations from all rules, flattened into a single list. */
  public List<Violation> allViolations() {
    return results.stream().flatMap(r -> r.violations().stream()).toList();
  }

  /**
   * Whether any executed rule produced a build-blocking violation.
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
    return scannedElementCount == other.scannedElementCount
        && projectName.equals(other.projectName)
        && timestamp.equals(other.timestamp)
        && results.equals(other.results)
        && totalExecutionTime.equals(other.totalExecutionTime)
        && woguVersion.equals(other.woguVersion)
        && javaVersion.equals(other.javaVersion)
        && buildTool.equals(other.buildTool);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        projectName, timestamp, results, totalExecutionTime, scannedElementCount, woguVersion, javaVersion, buildTool);
  }

  @Override
  public String toString() {
    return "ValidationSummary{projectName=%s, rules=%d, violations=%d, hasBuildFailures=%s}"
        .formatted(projectName, results.size(), allViolations().size(), hasBuildFailures());
  }

  /** Builder for {@link ValidationSummary}. */
  public static final class Builder {

    private String projectName;
    private Instant timestamp;
    private List<RuleResult> results = List.of();
    private Duration totalExecutionTime;
    private int scannedElementCount;
    private String woguVersion;
    private String javaVersion;
    private String buildTool;

    private Builder() {}

    public Builder projectName(String projectName) {
      this.projectName = projectName;
      return this;
    }

    public Builder timestamp(Instant timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder results(List<RuleResult> results) {
      this.results = List.copyOf(results);
      return this;
    }

    public Builder totalExecutionTime(Duration totalExecutionTime) {
      this.totalExecutionTime = totalExecutionTime;
      return this;
    }

    public Builder scannedElementCount(int scannedElementCount) {
      this.scannedElementCount = scannedElementCount;
      return this;
    }

    public Builder woguVersion(String woguVersion) {
      this.woguVersion = woguVersion;
      return this;
    }

    public Builder javaVersion(String javaVersion) {
      this.javaVersion = javaVersion;
      return this;
    }

    public Builder buildTool(String buildTool) {
      this.buildTool = buildTool;
      return this;
    }

    public ValidationSummary build() {
      return new ValidationSummary(this);
    }
  }
}
