package io.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wogu.api.Rule;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleRegistryTest {

  @Test
  void loadsExactlyWG001ThroughWG010FromTheClasspath() {
    List<TemporalRule> rules = RuleRegistry.loadDeclarativeRules(getClass().getClassLoader());

    assertThat(rules)
        .extracting(rule -> rule.metadata().id())
        .containsExactlyInAnyOrder(
            "WG001", "WG002", "WG003", "WG004", "WG005", "WG006", "WG007", "WG008", "WG009", "WG010");
  }

  @Test
  void everyLoadedRuleIsAForbiddenMethodRule() {
    List<TemporalRule> rules = RuleRegistry.loadDeclarativeRules(getClass().getClassLoader());

    assertThat(rules).allSatisfy(rule -> assertThat(rule).isInstanceOf(ForbiddenMethodRule.class));
  }

  @Test
  void rejectsADefinitionDeclaringAnUnknownType() {
    RuleDefinition unknownType =
        new RuleDefinition(
            "WG900",
            "not-a-real-type",
            "Example",
            "Example description",
            "Determinism",
            "ERROR",
            "Temporal Java SDK",
            "0.2.0",
            "docs/rules/WG900.md",
            "Example replacement",
            List.of(),
            List.of(),
            List.of());

    assertThatThrownBy(() -> RuleRegistry.create(unknownType))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WG900")
        .hasMessageContaining("not-a-real-type");
  }

  @Test
  void createsAForbiddenMethodRuleWithMetadataMatchingTheDefinition() {
    RuleDefinition definition =
        new RuleDefinition(
            "WG050",
            "forbidden-method",
            "Example Rule",
            "Example description",
            "Determinism",
            "ERROR",
            "Temporal Java SDK",
            "0.2.0",
            "docs/rules/WG050.md",
            "Example replacement",
            List.of("java.util.UUID.randomUUID"),
            List.of(),
            List.of());

    TemporalRule rule = RuleRegistry.create(definition);
    Rule metadata = rule.metadata();

    assertThat(metadata.id()).isEqualTo("WG050");
    assertThat(metadata.title()).isEqualTo("Example Rule");
    assertThat(metadata.documentationReference()).isEqualTo("docs/rules/WG050.md");
  }
}
