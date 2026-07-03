package io.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuleTest {

  private static Rule.Builder validBuilder() {
    return Rule.builder()
        .id("WG001")
        .title("UUID.randomUUID() inside Workflow")
        .category(RuleCategory.DETERMINISM)
        .severity(Severity.ERROR)
        .engine("Temporal Java SDK")
        .sinceVersion("0.1.0")
        .documentationReference("docs/rules/WG001.md");
  }

  @Test
  void buildsWithAllFieldsPopulated() {
    Rule rule = validBuilder().build();

    assertThat(rule.id()).isEqualTo("WG001");
    assertThat(rule.title()).isEqualTo("UUID.randomUUID() inside Workflow");
    assertThat(rule.category()).isEqualTo(RuleCategory.DETERMINISM);
    assertThat(rule.severity()).isEqualTo(Severity.ERROR);
    assertThat(rule.engine()).isEqualTo("Temporal Java SDK");
    assertThat(rule.sinceVersion()).isEqualTo("0.1.0");
    assertThat(rule.documentationReference()).isEqualTo("docs/rules/WG001.md");
    assertThat(rule.autoFixAvailable()).isFalse();
  }

  @Test
  void rejectsRuleIdOutsideItsCategorysRange() {
    Rule.Builder builder = validBuilder().id("WG150").category(RuleCategory.DETERMINISM);

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WG150")
        .hasMessageContaining("DETERMINISM");
  }

  @Test
  void acceptsRuleIdWithinItsCategorysRange() {
    Rule rule = validBuilder().id("WG100").category(RuleCategory.ACTIVITIES).build();

    assertThat(rule.id()).isEqualTo("WG100");
  }

  @Test
  void equalsAndHashCodeAreValueBased() {
    assertThat(validBuilder().build()).isEqualTo(validBuilder().build()).hasSameHashCodeAs(validBuilder().build());
  }
}
