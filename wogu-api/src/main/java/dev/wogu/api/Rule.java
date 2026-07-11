package dev.wogu.api;

import java.util.Objects;

/**
 * Static metadata describing one workflow-quality rule, independent of any particular
 * violation of it.
 *
 * <p>A {@link WorkflowValidator} implementation may evaluate many rules in a single pass
 * (e.g. a Temporal determinism validator checking for {@code UUID.randomUUID()},
 * {@code Thread.sleep()}, and {@code Instant.now()} all in one traversal) — {@code Rule}
 * is what the report and future configuration (enabling/disabling a specific rule by id)
 * key off of, not the validator implementation that happens to produce it.
 *
 * <p>{@code wogu-report} renders reports from this metadata alone: adding a new rule never
 * requires a report code change, only a new {@code Rule} instance and the logic that
 * evaluates it.
 *
 * <p>Instances are immutable. Use {@link #builder()} to construct one.
 */
public final class Rule {

  private final String id;
  private final String title;
  private final RuleCategory category;
  private final Severity severity;
  private final String engine;
  private final String sinceVersion;
  private final String documentationReference;
  private final boolean autoFixAvailable;

  private Rule(Builder builder) {
    this.id = Objects.requireNonNull(builder.id, "id");
    this.title = Objects.requireNonNull(builder.title, "title");
    this.category = Objects.requireNonNull(builder.category, "category");
    this.severity = Objects.requireNonNull(builder.severity, "severity");
    this.engine = Objects.requireNonNull(builder.engine, "engine");
    this.sinceVersion = Objects.requireNonNull(builder.sinceVersion, "sinceVersion");
    this.documentationReference = Objects.requireNonNull(builder.documentationReference, "documentationReference");
    this.autoFixAvailable = builder.autoFixAvailable;
    if (!category.containsRuleId(id)) {
      throw new IllegalArgumentException(
          "Rule id %s is not in %s's reserved range WG%03d-WG%03d"
              .formatted(id, category, category.rangeStart(), category.rangeEnd()));
    }
  }

  /** Stable rule id in {@code WG###} form, e.g. {@code "WG001"}. Never changes once published. */
  public String id() {
    return id;
  }

  /** Short, human-readable title, e.g. {@code "UUID.randomUUID() inside Workflow"}. */
  public String title() {
    return title;
  }

  /** The category this rule belongs to, which also fixes its {@code WG###} numeric range. */
  public RuleCategory category() {
    return category;
  }

  /** Severity a violation of this rule is reported at. */
  public Severity severity() {
    return severity;
  }

  /** Name of the workflow engine this rule applies to, e.g. {@code "Temporal Java SDK"}. */
  public String engine() {
    return engine;
  }

  /** WoGu version this rule was introduced in, e.g. {@code "0.1.0"}. */
  public String sinceVersion() {
    return sinceVersion;
  }

  /**
   * Where to read more about this rule: a path relative to the project root today (e.g.
   * {@code "docs/rules/WG001.md"}), or a full URL once rule documentation is published
   * online (e.g. {@code "https://wogu.dev/rules/WG001"}). Report rendering treats this as
   * an opaque string and links to it as-is, so switching from one form to the other never
   * requires a report code change.
   */
  public String documentationReference() {
    return documentationReference;
  }

  /** Whether tooling can automatically fix a violation of this rule. Not yet supported by any rule. */
  public boolean autoFixAvailable() {
    return autoFixAvailable;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Rule other)) {
      return false;
    }
    return autoFixAvailable == other.autoFixAvailable
        && id.equals(other.id)
        && title.equals(other.title)
        && category == other.category
        && severity == other.severity
        && engine.equals(other.engine)
        && sinceVersion.equals(other.sinceVersion)
        && documentationReference.equals(other.documentationReference);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, title, category, severity, engine, sinceVersion, documentationReference, autoFixAvailable);
  }

  @Override
  public String toString() {
    return "%s: %s [%s/%s]".formatted(id, title, category, severity);
  }

  /** Builder for {@link Rule}. */
  public static final class Builder {

    private String id;
    private String title;
    private RuleCategory category;
    private Severity severity;
    private String engine;
    private String sinceVersion;
    private String documentationReference;
    private boolean autoFixAvailable = false;

    private Builder() {}

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder title(String title) {
      this.title = title;
      return this;
    }

    public Builder category(RuleCategory category) {
      this.category = category;
      return this;
    }

    public Builder severity(Severity severity) {
      this.severity = severity;
      return this;
    }

    public Builder engine(String engine) {
      this.engine = engine;
      return this;
    }

    public Builder sinceVersion(String sinceVersion) {
      this.sinceVersion = sinceVersion;
      return this;
    }

    public Builder documentationReference(String documentationReference) {
      this.documentationReference = documentationReference;
      return this;
    }

    public Builder autoFixAvailable(boolean autoFixAvailable) {
      this.autoFixAvailable = autoFixAvailable;
      return this;
    }

    public Rule build() {
      return new Rule(this);
    }
  }
}
