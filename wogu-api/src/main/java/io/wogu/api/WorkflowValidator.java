package io.wogu.api;

import java.util.List;

/**
 * A validator implementation for one workflow engine, capable of evaluating one or more
 * {@link Rule}s in a single pass over a project.
 *
 * <p>This is the extension point of WoGu. Adding a new validator to the framework never
 * requires touching {@code wogu-core} or any other existing module:
 *
 * <ol>
 *   <li>Implement this interface.
 *   <li>Register the implementation as a service provider, via either a
 *       {@code META-INF/services/io.wogu.api.WorkflowValidator} file listing the
 *       implementation's fully qualified name, or a module-info {@code provides} clause.
 * </ol>
 *
 * <p>The engine ({@code wogu-core}'s {@code ValidationEngine}) discovers implementations
 * with {@link java.util.ServiceLoader} and has no compile-time knowledge of any specific
 * validator or rule. There is no registry to edit and no switch statement to extend.
 *
 * <p>A single implementation commonly evaluates several rules that share expensive setup
 * (e.g. parsing every source file once, building one call graph) — {@link Rule} and
 * {@link RuleResult}, not this interface, are what reports and future per-rule
 * configuration key off of.
 *
 * <p>Implementations must be stateless and thread-safe: a single instance may be reused
 * across multiple {@link #validate(ValidationContext)} calls.
 */
public interface WorkflowValidator {

  /**
   * Stable, unique identifier for this validator implementation, used in log output.
   *
   * <p>Convention: a short lowerCamelCase or kebab-case name describing the engine
   * integration, e.g. {@code "wogu-temporal"}. Distinct from a {@link Rule#id()}: one
   * validator id may cover many rule ids.
   */
  String id();

  /**
   * One-sentence, human-readable description of what this validator does, shown in
   * console output.
   */
  String description();

  /**
   * The metadata for every rule this validator is capable of evaluating.
   *
   * <p>{@link #validate(ValidationContext)} should return exactly one {@link RuleResult}
   * per entry here on every call (with an empty violation list for rules that found
   * nothing), so the report can show a rule's {@code PASSED} status even when it produced
   * no violations.
   */
  List<Rule> rules();

  /**
   * Runs this validator against the given project.
   *
   * <p>Implementations must not throw for expected conditions (e.g. no matching classes
   * found); a {@link RuleResult} with no violations is the correct way to report "nothing
   * to flag" for a given rule. Only unrecoverable errors (e.g. an unreadable source root)
   * should propagate as exceptions, which the engine treats as an infrastructure failure
   * distinct from a validation failure.
   *
   * @param context the project to validate
   * @return the rule results produced by this run, plus diagnostic scan statistics
   */
  ValidatorRunOutcome validate(ValidationContext context);
}
