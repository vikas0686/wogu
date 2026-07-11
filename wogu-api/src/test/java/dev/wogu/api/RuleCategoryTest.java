package dev.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuleCategoryTest {

  @Test
  void determinismRangeCoversWG001ThroughWG099() {
    assertThat(RuleCategory.DETERMINISM.containsRuleId("WG001")).isTrue();
    assertThat(RuleCategory.DETERMINISM.containsRuleId("WG099")).isTrue();
    assertThat(RuleCategory.DETERMINISM.containsRuleId("WG100")).isFalse();
  }

  @Test
  void activitiesRangeCoversWG100ThroughWG199() {
    assertThat(RuleCategory.ACTIVITIES.containsRuleId("WG100")).isTrue();
    assertThat(RuleCategory.ACTIVITIES.containsRuleId("WG199")).isTrue();
    assertThat(RuleCategory.ACTIVITIES.containsRuleId("WG099")).isFalse();
    assertThat(RuleCategory.ACTIVITIES.containsRuleId("WG200")).isFalse();
  }

  @Test
  void organizationPoliciesRangeCoversWG900ThroughWG999() {
    assertThat(RuleCategory.ORGANIZATION_POLICIES.containsRuleId("WG900")).isTrue();
    assertThat(RuleCategory.ORGANIZATION_POLICIES.containsRuleId("WG999")).isTrue();
    assertThat(RuleCategory.ORGANIZATION_POLICIES.containsRuleId("WG899")).isFalse();
  }

  @Test
  void rejectsIdsNotStartingWithWG() {
    assertThatThrownBy(() -> RuleCategory.DETERMINISM.containsRuleId("XX001"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonNumericSuffix() {
    assertThatThrownBy(() -> RuleCategory.DETERMINISM.containsRuleId("WGabc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void categoryRangesDoNotOverlap() {
    RuleCategory[] categories = RuleCategory.values();
    for (RuleCategory category : categories) {
      assertThat(category.rangeStart()).isLessThanOrEqualTo(category.rangeEnd());
      for (RuleCategory other : categories) {
        if (category == other) {
          continue;
        }
        boolean overlaps = category.rangeStart() <= other.rangeEnd() && other.rangeStart() <= category.rangeEnd();
        assertThat(overlaps).as("%s and %s must not overlap", category, other).isFalse();
      }
    }
  }
}
