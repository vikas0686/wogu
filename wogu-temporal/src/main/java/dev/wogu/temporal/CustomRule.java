package dev.wogu.temporal;

import dev.wogu.api.Rule;
import java.util.Objects;

/**
 * Base class for hand-written Temporal rules that need analysis beyond what a
 * declarative rule type (like {@link ForbiddenMethodRule}) can express — for example a
 * future workflow-complexity check, a ContinueAsNew recommendation, a versioning-safety
 * check, or activity configuration validation. These need real Java logic, not a
 * YAML-describable pattern match.
 *
 * <p>Most rules should <em>not</em> extend this: if a rule can be expressed as "flag this
 * method call reachable from a workflow", it belongs in a YAML definition under
 * {@code src/main/resources/rules} using the {@code forbidden-method} type, not as a new
 * class here. This class exists for the minority of rules that genuinely can't be.
 *
 * <p>A subclass only needs to supply its {@link Rule} metadata and implement
 * {@link TemporalRule#evaluate}; nothing else about how rules are registered or executed
 * changes based on whether a rule is declarative or custom.
 */
abstract class CustomRule implements TemporalRule {

  private final Rule metadata;

  protected CustomRule(Rule metadata) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
  }

  @Override
  public final Rule metadata() {
    return metadata;
  }
}
