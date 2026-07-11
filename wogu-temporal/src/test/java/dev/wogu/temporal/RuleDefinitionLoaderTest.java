package dev.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuleDefinitionLoaderTest {

  private final RuleDefinitionLoader loader = new RuleDefinitionLoader(getClass().getClassLoader());

  @Test
  void parsesAWellFormedForbiddenMethodDefinition() {
    RuleDefinition definition =
        loader.parse(
            """
            id: WG900
            type: forbidden-method
            title: Example Rule
            category: Determinism
            severity: ERROR
            engine: Temporal Java SDK
            since: 0.2.0
            documentation: docs/rules/WG900.md
            description: Example description.
            replacement: Example replacement.
            methods:
              - java.util.UUID.randomUUID
              - java.lang.Thread.sleep
            """,
            "test.yaml");

    assertThat(definition.id()).isEqualTo("WG900");
    assertThat(definition.type()).isEqualTo("forbidden-method");
    assertThat(definition.title()).isEqualTo("Example Rule");
    assertThat(definition.category()).isEqualTo("Determinism");
    assertThat(definition.severity()).isEqualTo("ERROR");
    assertThat(definition.engine()).isEqualTo("Temporal Java SDK");
    assertThat(definition.sinceVersion()).isEqualTo("0.2.0");
    assertThat(definition.documentation()).isEqualTo("docs/rules/WG900.md");
    assertThat(definition.description()).isEqualTo("Example description.");
    assertThat(definition.replacement()).isEqualTo("Example replacement.");
    assertThat(definition.methods()).containsExactly("java.util.UUID.randomUUID", "java.lang.Thread.sleep");
    assertThat(definition.tags()).isEmpty();
  }

  @Test
  void parsesTagsWhenPresent() {
    RuleDefinition definition =
        loader.parse(
            """
            id: WG901
            type: forbidden-method
            title: Example Rule
            category: Determinism
            severity: ERROR
            engine: Temporal Java SDK
            since: 0.2.0
            documentation: docs/rules/WG901.md
            description: Example description.
            replacement: Example replacement.
            methods:
              - java.util.UUID.randomUUID
            tags:
              - determinism
              - experimental
            """,
            "test.yaml");

    assertThat(definition.tags()).containsExactly("determinism", "experimental");
  }

  @Test
  void parsesSuppressedContextsWhenPresent() {
    RuleDefinition definition =
        loader.parse(
            """
            id: WG902
            type: forbidden-method
            title: Example Rule
            category: Determinism
            severity: ERROR
            engine: Temporal Java SDK
            since: 0.2.0
            documentation: docs/rules/WG902.md
            description: Example description.
            replacement: Example replacement.
            methods:
              - java.util.UUID.randomUUID
            suppressedContexts:
              - SIDE_EFFECT
            """,
            "test.yaml");

    assertThat(definition.suppressedContexts()).containsExactly("SIDE_EFFECT");
  }

  @Test
  void defaultsSuppressedContextsToEmptyWhenAbsent() {
    RuleDefinition definition =
        loader.parse(
            """
            id: WG900
            type: forbidden-method
            title: Example Rule
            category: Determinism
            severity: ERROR
            engine: Temporal Java SDK
            since: 0.2.0
            documentation: docs/rules/WG900.md
            description: Example description.
            replacement: Example replacement.
            methods:
              - java.util.UUID.randomUUID
            """,
            "test.yaml");

    assertThat(definition.suppressedContexts()).isEmpty();
  }

  @Test
  void parsesRequiredContextsWhenPresent() {
    RuleDefinition definition =
        loader.parse(
            """
            id: WG011
            type: forbidden-method
            title: Example Rule
            category: Determinism
            severity: ERROR
            engine: Temporal Java SDK
            since: 1.1.0
            documentation: docs/rules/WG011.md
            description: Example description.
            replacement: Example replacement.
            constructors:
              - java.net.Socket
            requiredContexts:
              - SIDE_EFFECT
              - MUTABLE_SIDE_EFFECT
            """,
            "test.yaml");

    assertThat(definition.requiredContexts()).containsExactly("SIDE_EFFECT", "MUTABLE_SIDE_EFFECT");
  }

  @Test
  void defaultsRequiredContextsToEmptyWhenAbsent() {
    RuleDefinition definition =
        loader.parse(
            """
            id: WG900
            type: forbidden-method
            title: Example Rule
            category: Determinism
            severity: ERROR
            engine: Temporal Java SDK
            since: 0.2.0
            documentation: docs/rules/WG900.md
            description: Example description.
            replacement: Example replacement.
            methods:
              - java.util.UUID.randomUUID
            """,
            "test.yaml");

    assertThat(definition.requiredContexts()).isEmpty();
  }

  @Test
  void rejectsADefinitionMissingARequiredField() {
    String missingSeverity =
        """
        id: WG900
        type: forbidden-method
        title: Example Rule
        category: Determinism
        engine: Temporal Java SDK
        since: 0.2.0
        documentation: docs/rules/WG900.md
        description: Example description.
        replacement: Example replacement.
        methods:
          - java.util.UUID.randomUUID
        """;

    assertThatThrownBy(() -> loader.parse(missingSeverity, "wg900.yaml"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wg900.yaml")
        .hasMessageContaining("severity");
  }

  @Test
  void rejectsAnEmptyDefinition() {
    assertThatThrownBy(() -> loader.parse("", "empty.yaml"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty.yaml");
  }

  @Test
  void loadAllFindsTheRealRuleDefinitionsPackagedInThisModule() {
    List<RuleDefinition> definitions = loader.loadAll();

    assertThat(definitions)
        .extracting(RuleDefinition::id)
        .containsExactlyInAnyOrder(
            "WG001", "WG002", "WG003", "WG004", "WG005", "WG006", "WG007", "WG008", "WG009", "WG010", "WG011");
    assertThat(definitions).allSatisfy(definition -> assertThat(definition.type()).isEqualTo("forbidden-method"));
  }
}
