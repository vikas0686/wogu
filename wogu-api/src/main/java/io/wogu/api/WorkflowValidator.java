package io.wogu.api;

/**
 * A single, self-contained workflow correctness rule.
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
 * validator. There is no registry to edit and no switch statement to extend.
 *
 * <p>Implementations must be stateless and thread-safe: a single instance may be reused
 * across multiple {@link #validate(ValidationContext)} calls.
 */
public interface WorkflowValidator {

  /**
   * Stable, unique identifier for this validator, used in reports and log output.
   *
   * <p>Convention: a short lowerCamelCase or kebab-case name describing the rule, e.g.
   * {@code "uuid-random-in-workflow"}. Must not change between releases, since build
   * tooling (e.g. suppression configuration) may reference it.
   */
  String id();

  /**
   * One-sentence, human-readable description of what this validator checks, shown in
   * console output and HTML reports.
   */
  String description();

  /**
   * Runs this validator against the given project.
   *
   * <p>Implementations must not throw for expected conditions (e.g. no matching classes
   * found); an empty {@link ValidationResult} is the correct way to report "nothing to
   * flag". Only unrecoverable errors (e.g. an unreadable source root) should propagate as
   * exceptions, which the engine treats as an infrastructure failure distinct from a
   * validation failure.
   *
   * @param context the project to validate
   * @return the violations found, if any
   */
  ValidationResult validate(ValidationContext context);
}
