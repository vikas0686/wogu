package io.wogu.core;

import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationResult;
import io.wogu.api.ValidationSummary;
import io.wogu.api.WorkflowValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and runs {@link WorkflowValidator} implementations.
 *
 * <p>Validators are found with {@link ServiceLoader}: any jar on the classpath that
 * contains a {@code META-INF/services/io.wogu.api.WorkflowValidator} file listing an
 * implementation is picked up automatically by {@link #discover()}. This engine has no
 * compile-time reference to any specific validator (e.g. {@code UUIDRandomValidator} in
 * {@code wogu-temporal}) — new validators, including ones for entirely new workflow
 * engines, are added purely by publishing a jar with a service declaration, never by
 * modifying this class.
 *
 * <p>Instances are immutable and safe to reuse across multiple {@link #run} calls.
 */
public final class ValidationEngine {

  private static final Logger LOG = LoggerFactory.getLogger(ValidationEngine.class);

  private final List<WorkflowValidator> validators;

  private ValidationEngine(List<WorkflowValidator> validators) {
    this.validators = List.copyOf(validators);
  }

  /**
   * Discovers all {@link WorkflowValidator} implementations visible to the given class
   * loader via {@link ServiceLoader}.
   *
   * @param classLoader class loader to search for {@code META-INF/services} declarations
   */
  public static ValidationEngine discover(ClassLoader classLoader) {
    List<WorkflowValidator> discovered = new ArrayList<>();
    ServiceLoader.load(WorkflowValidator.class, classLoader).forEach(discovered::add);
    LOG.debug("Discovered {} validator(s): {}", discovered.size(), ids(discovered));
    return new ValidationEngine(discovered);
  }

  /**
   * Discovers all {@link WorkflowValidator} implementations visible to this class's own
   * class loader. Equivalent to {@code discover(ValidationEngine.class.getClassLoader())}.
   */
  public static ValidationEngine discover() {
    return discover(ValidationEngine.class.getClassLoader());
  }

  /**
   * Creates an engine from an explicit set of validators, bypassing {@link ServiceLoader}
   * discovery. Intended for tests and for callers that need precise control over which
   * validators run.
   */
  public static ValidationEngine of(List<WorkflowValidator> validators) {
    return new ValidationEngine(List.copyOf(Objects.requireNonNull(validators, "validators")));
  }

  /** The validators this engine will execute, in discovery/registration order. */
  public List<WorkflowValidator> validators() {
    return validators;
  }

  /**
   * Runs every registered validator against {@code context} and aggregates the results.
   *
   * @param context the project to validate
   * @return a summary containing one {@link ValidationResult} per validator
   * @throws ValidatorExecutionException if a validator throws instead of returning a result
   */
  public ValidationSummary run(ValidationContext context) {
    Objects.requireNonNull(context, "context");
    Instant runStart = Instant.now();
    List<ValidationResult> results = new ArrayList<>(validators.size());

    for (WorkflowValidator validator : validators) {
      LOG.info("Executing validator: {}", validator.id());
      Instant validatorStart = Instant.now();
      ValidationResult result;
      try {
        result = validator.validate(context);
      } catch (RuntimeException e) {
        throw new ValidatorExecutionException(validator.id(), e);
      }
      Duration elapsed = Duration.between(validatorStart, Instant.now());
      LOG.info(
          "Validator {} {} ({} violation(s), {} ms)",
          validator.id(),
          result.passed() ? "PASSED" : "FAILED",
          result.violations().size(),
          elapsed.toMillis());
      results.add(result);
    }

    Duration totalElapsed = Duration.between(runStart, Instant.now());
    return ValidationSummary.of(context.projectName(), Instant.now(), results, totalElapsed);
  }

  private static List<String> ids(List<WorkflowValidator> validators) {
    return validators.stream().map(WorkflowValidator::id).toList();
  }
}
