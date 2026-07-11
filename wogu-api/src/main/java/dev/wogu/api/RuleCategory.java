package dev.wogu.api;

/**
 * WoGu's cross-engine classification of what a {@link Rule} checks.
 *
 * <p>Every category owns a reserved range of rule id numbers (the digits in
 * {@code WG###}), so a rule's id alone indicates its category without needing to look up
 * its metadata. New engine modules reuse these categories rather than inventing their own,
 * keeping the taxonomy consistent across Temporal, Conductor, Camunda, Airflow, etc.
 */
public enum RuleCategory {
  DETERMINISM(1, 99),
  ACTIVITIES(100, 199),
  VERSIONING(200, 299),
  SIGNALS(300, 349),
  UPDATES(350, 399),
  PERFORMANCE(400, 499),
  BEST_PRACTICES(500, 599),
  SECURITY(600, 699),
  ORGANIZATION_POLICIES(900, 999);

  private final int rangeStart;
  private final int rangeEnd;

  RuleCategory(int rangeStart, int rangeEnd) {
    this.rangeStart = rangeStart;
    this.rangeEnd = rangeEnd;
  }

  /** Lowest rule id number (inclusive) reserved for this category, e.g. {@code 1} for WG001. */
  public int rangeStart() {
    return rangeStart;
  }

  /** Highest rule id number (inclusive) reserved for this category, e.g. {@code 99} for WG099. */
  public int rangeEnd() {
    return rangeEnd;
  }

  /**
   * Whether a rule id such as {@code "WG001"} falls within this category's reserved
   * numeric range.
   *
   * @param ruleId a rule id in {@code WG###} form
   * @throws IllegalArgumentException if {@code ruleId} isn't in {@code WG} followed by digits
   */
  public boolean containsRuleId(String ruleId) {
    return ruleNumber(ruleId) >= rangeStart && ruleNumber(ruleId) <= rangeEnd;
  }

  private static int ruleNumber(String ruleId) {
    if (!ruleId.startsWith("WG")) {
      throw new IllegalArgumentException("Rule id must start with 'WG': " + ruleId);
    }
    try {
      return Integer.parseInt(ruleId.substring(2));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Rule id must be 'WG' followed by digits: " + ruleId, e);
    }
  }
}
