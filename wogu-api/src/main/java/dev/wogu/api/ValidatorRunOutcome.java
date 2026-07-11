package dev.wogu.api;

import java.util.List;
import java.util.Objects;

/**
 * What a single {@link WorkflowValidator#validate(ValidationContext)} call produced: one
 * {@link RuleResult} per rule it evaluated, plus how many top-level units it scanned
 * (e.g. workflow implementation classes for Temporal, process definitions for Camunda),
 * used only for console/log diagnostics such as {@code "Found 4 workflow classes"} — it
 * has no bearing on pass/fail and is not rendered in the HTML report.
 */
public final class ValidatorRunOutcome {

  private final List<RuleResult> ruleResults;
  private final int scannedElementCount;

  private ValidatorRunOutcome(List<RuleResult> ruleResults, int scannedElementCount) {
    this.ruleResults = List.copyOf(Objects.requireNonNull(ruleResults, "ruleResults"));
    if (scannedElementCount < 0) {
      throw new IllegalArgumentException("scannedElementCount must be >= 0, was " + scannedElementCount);
    }
    this.scannedElementCount = scannedElementCount;
  }

  /**
   * @param ruleResults one result per rule the validator evaluated
   * @param scannedElementCount number of top-level units the validator scanned, for
   *     diagnostic logging only
   */
  public static ValidatorRunOutcome of(List<RuleResult> ruleResults, int scannedElementCount) {
    return new ValidatorRunOutcome(ruleResults, scannedElementCount);
  }

  /** One result per rule the validator evaluated. */
  public List<RuleResult> ruleResults() {
    return ruleResults;
  }

  /** Number of top-level units scanned (e.g. workflow implementation classes), for diagnostics only. */
  public int scannedElementCount() {
    return scannedElementCount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ValidatorRunOutcome other)) {
      return false;
    }
    return scannedElementCount == other.scannedElementCount && ruleResults.equals(other.ruleResults);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ruleResults, scannedElementCount);
  }

  @Override
  public String toString() {
    return "ValidatorRunOutcome{rules=%d, scannedElementCount=%d}".formatted(ruleResults.size(), scannedElementCount);
  }
}
