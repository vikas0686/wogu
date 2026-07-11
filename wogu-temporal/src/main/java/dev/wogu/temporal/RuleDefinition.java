package dev.wogu.temporal;

import dev.wogu.api.Rule;
import dev.wogu.api.RuleCategory;
import dev.wogu.api.Severity;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A strongly-typed, validated rule definition — the in-memory shape every declarative
 * rule is loaded into by {@link RuleDefinitionLoader}, regardless of the file format it
 * came from. Nothing downstream of the loader (the {@link RuleRegistry}, the various
 * {@link TemporalRule} implementations) ever sees raw YAML; they only see this type,
 * which is what keeps them testable without a config file on disk and keeps the door open
 * to a different configuration format later without touching them.
 *
 * @param id stable rule id, e.g. {@code "WG001"}
 * @param type which {@link TemporalRule} implementation understands this definition, e.g.
 *     {@code "forbidden-method"} — see {@link RuleRegistry}
 * @param title short human-readable title, e.g. {@code "UUID.randomUUID() inside Workflow"}
 * @param description longer, teaching-style explanation of the problem; becomes a
 *     {@link dev.wogu.api.Violation#message()}
 * @param category name of a {@link RuleCategory} constant (case/spacing-insensitive, e.g.
 *     {@code "Determinism"} or {@code "Best Practices"})
 * @param severity name of a {@link Severity} constant, e.g. {@code "ERROR"}
 * @param engine name of the workflow engine this rule applies to
 * @param sinceVersion WoGu version this rule was introduced in
 * @param documentation path or URL to this rule's documentation
 * @param replacement suggested fix text; becomes a {@link dev.wogu.api.Violation#suggestedFix()}
 * @param methods for {@code forbidden-method} rules: fully qualified {@code Class.method}
 *     references, e.g. {@code "java.util.UUID.randomUUID"}; empty for other types
 * @param constructors for {@code forbidden-method} rules that also (or instead) flag
 *     constructing a specific class: fully qualified class names, e.g.
 *     {@code "java.util.Random"}; empty for rules that only flag method calls
 * @param suppressedContexts names of {@link dev.wogu.temporal.callgraph.ExecutionContext}
 *     constants (e.g. {@code "SIDE_EFFECT"}) this rule does not apply in; empty for a rule
 *     that fires the same regardless of execution context. Interpreting these names is up
 *     to the rule type ({@link ForbiddenMethodRule} converts them to
 *     {@code Set<ExecutionContext>}) — this record only carries the raw, validated strings.
 * @param tags free-form labels, reserved for future use (not yet rendered anywhere)
 */
record RuleDefinition(
    String id,
    String type,
    String title,
    String description,
    String category,
    String severity,
    String engine,
    String sinceVersion,
    String documentation,
    String replacement,
    List<String> methods,
    List<String> constructors,
    List<String> suppressedContexts,
    List<String> tags) {

  RuleDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(engine, "engine");
    Objects.requireNonNull(sinceVersion, "sinceVersion");
    Objects.requireNonNull(documentation, "documentation");
    Objects.requireNonNull(replacement, "replacement");
    methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    constructors = List.copyOf(Objects.requireNonNull(constructors, "constructors"));
    suppressedContexts = List.copyOf(Objects.requireNonNull(suppressedContexts, "suppressedContexts"));
    tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
  }

  /**
   * Maps this definition's common metadata fields (everything except {@link #methods()},
   * which is specific to the {@code forbidden-method} type) onto {@code wogu-api}'s
   * {@link Rule} model. Every declarative rule type shares this conversion.
   */
  Rule toRule() {
    return Rule.builder()
        .id(id)
        .title(title)
        .category(RuleCategory.valueOf(normalizeEnumName(category)))
        .severity(Severity.valueOf(normalizeEnumName(severity)))
        .engine(engine)
        .sinceVersion(sinceVersion)
        .documentationReference(documentation)
        .build();
  }

  private static String normalizeEnumName(String value) {
    return value.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
  }
}
