package io.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidatorRunOutcomeTest {

  private static Rule rule() {
    return Rule.builder()
        .id("WG001")
        .title("t")
        .category(RuleCategory.DETERMINISM)
        .severity(Severity.ERROR)
        .engine("Temporal Java SDK")
        .sinceVersion("0.1.0")
        .documentationReference("docs/rules/WG001.md")
        .build();
  }

  @Test
  void exposesRuleResultsAndScannedElementCount() {
    RuleResult result = RuleResult.of(rule(), List.of(), Duration.ofMillis(1));
    ValidatorRunOutcome outcome = ValidatorRunOutcome.of(List.of(result), 4);

    assertThat(outcome.ruleResults()).containsExactly(result);
    assertThat(outcome.scannedElementCount()).isEqualTo(4);
  }

  @Test
  void rejectsNegativeScannedElementCount() {
    assertThatThrownBy(() -> ValidatorRunOutcome.of(List.of(), -1)).isInstanceOf(IllegalArgumentException.class);
  }
}
