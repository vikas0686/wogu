package io.wogu.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of running a single {@link WorkflowValidator}: the violations it found
 * (possibly empty) and how long it took.
 *
 * <p>A result is considered passing when it contains no violation whose
 * {@link Severity#blocksBuild()} is {@code true}; {@link Severity#WARNING} and
 * {@link Severity#INFO} findings are surfaced in reports but do not fail a validator.
 */
public final class ValidationResult {

  private final String validatorId;
  private final List<Violation> violations;
  private final Duration executionTime;

  private ValidationResult(String validatorId, List<Violation> violations, Duration executionTime) {
    this.validatorId = Objects.requireNonNull(validatorId, "validatorId");
    this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    this.executionTime = Objects.requireNonNull(executionTime, "executionTime");
  }

  /**
   * Creates a result for a validator run.
   *
   * @param validatorId id of the {@link WorkflowValidator} that produced this result
   * @param violations violations found, in no particular order; may be empty
   * @param executionTime wall-clock time the validator took to run
   */
  public static ValidationResult of(String validatorId, List<Violation> violations, Duration executionTime) {
    return new ValidationResult(validatorId, violations, executionTime);
  }

  /** Id of the validator this result belongs to. */
  public String validatorId() {
    return validatorId;
  }

  /** Immutable list of violations found by the validator; empty if none. */
  public List<Violation> violations() {
    return violations;
  }

  /** Wall-clock time the validator took to run. */
  public Duration executionTime() {
    return executionTime;
  }

  /**
   * Whether this validator run passed, i.e. contains no build-blocking violation.
   *
   * @return {@code true} if no violation has a severity that blocks the build
   */
  public boolean passed() {
    return violations.stream().noneMatch(v -> v.severity().blocksBuild());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ValidationResult other)) {
      return false;
    }
    return validatorId.equals(other.validatorId)
        && violations.equals(other.violations)
        && executionTime.equals(other.executionTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(validatorId, violations, executionTime);
  }

  @Override
  public String toString() {
    return "ValidationResult{validatorId=%s, passed=%s, violations=%d, executionTime=%s}"
        .formatted(validatorId, passed(), violations.size(), executionTime);
  }
}
