package dev.wogu.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of evaluating a single {@link Rule}: the violations it found (possibly
 * empty) and how long evaluation took.
 *
 * <p>A single {@link WorkflowValidator} implementation may evaluate many rules in one
 * {@link WorkflowValidator#validate(ValidationContext)} call — this type is what the
 * engine, the report, and build-tool integrations key off of, one instance per rule that
 * was evaluated, not one per validator implementation.
 *
 * <p>A result is considered passing when it contains no violation whose
 * {@link Severity#blocksBuild()} is {@code true}; {@link Severity#WARNING} and
 * {@link Severity#INFO} findings are surfaced in reports but do not fail a rule.
 */
public final class RuleResult {

  private final Rule rule;
  private final List<Violation> violations;
  private final Duration executionTime;

  private RuleResult(Rule rule, List<Violation> violations, Duration executionTime) {
    this.rule = Objects.requireNonNull(rule, "rule");
    this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    this.executionTime = Objects.requireNonNull(executionTime, "executionTime");
  }

  /**
   * Creates a result for one rule's evaluation.
   *
   * @param rule the rule that was evaluated
   * @param violations violations found, in no particular order; may be empty
   * @param executionTime wall-clock time evaluating this rule took
   */
  public static RuleResult of(Rule rule, List<Violation> violations, Duration executionTime) {
    return new RuleResult(rule, violations, executionTime);
  }

  /** The rule this result belongs to. */
  public Rule rule() {
    return rule;
  }

  /** Immutable list of violations found for this rule; empty if none. */
  public List<Violation> violations() {
    return violations;
  }

  /** Wall-clock time evaluating this rule took. */
  public Duration executionTime() {
    return executionTime;
  }

  /**
   * Whether this rule passed, i.e. contains no build-blocking violation.
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
    if (!(o instanceof RuleResult other)) {
      return false;
    }
    return rule.equals(other.rule) && violations.equals(other.violations) && executionTime.equals(other.executionTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(rule, violations, executionTime);
  }

  @Override
  public String toString() {
    return "RuleResult{rule=%s, passed=%s, violations=%d, executionTime=%s}"
        .formatted(rule.id(), passed(), violations.size(), executionTime);
  }
}
