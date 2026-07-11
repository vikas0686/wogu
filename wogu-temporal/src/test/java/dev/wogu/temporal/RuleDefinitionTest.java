package dev.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wogu.api.Rule;
import dev.wogu.api.RuleCategory;
import dev.wogu.api.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleDefinitionTest {

  private static RuleDefinition definitionWith(String id, String category, String severity) {
    return new RuleDefinition(
        id,
        "forbidden-method",
        "Example Rule",
        "Example description",
        category,
        severity,
        "Temporal Java SDK",
        "0.1.0",
        "docs/rules/" + id + ".md",
        "Example replacement",
        List.of("java.util.UUID.randomUUID"),
        List.of(),
        List.of(),
        List.of(),
        null,
        List.of());
  }

  @Test
  void mapsAllCommonFieldsOntoRule() {
    RuleDefinition definition = definitionWith("WG001", "Determinism", "ERROR");

    Rule rule = definition.toRule();

    assertThat(rule.id()).isEqualTo("WG001");
    assertThat(rule.title()).isEqualTo("Example Rule");
    assertThat(rule.category()).isEqualTo(RuleCategory.DETERMINISM);
    assertThat(rule.severity()).isEqualTo(Severity.ERROR);
    assertThat(rule.engine()).isEqualTo("Temporal Java SDK");
    assertThat(rule.sinceVersion()).isEqualTo("0.1.0");
    assertThat(rule.documentationReference()).isEqualTo("docs/rules/WG001.md");
  }

  @Test
  void mapsAMultiWordCategoryNameCaseInsensitively() {
    RuleDefinition definition = definitionWith("WG550", "best practices", "warning");

    Rule rule = definition.toRule();

    assertThat(rule.category()).isEqualTo(RuleCategory.BEST_PRACTICES);
    assertThat(rule.severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void mapsAHyphenatedCategoryName() {
    RuleDefinition definition = definitionWith("WG950", "Organization-Policies", "ERROR");

    Rule rule = definition.toRule();

    assertThat(rule.category()).isEqualTo(RuleCategory.ORGANIZATION_POLICIES);
  }
}
