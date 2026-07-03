package io.wogu.core;

import io.wogu.api.WorkflowValidator;

/**
 * Thrown when a {@link WorkflowValidator} throws while being executed by the
 * {@link ValidationEngine}.
 *
 * <p>This signals an infrastructure failure (e.g. an unreadable source root, a bug in the
 * validator itself) as distinct from a validation failure. A validator reporting that the
 * code it scanned is wrong should return a {@link io.wogu.api.ValidationResult} containing
 * violations, not throw; this exception exists for the case where the validator could not
 * complete its analysis at all.
 */
public final class ValidatorExecutionException extends RuntimeException {

  private final String validatorId;

  public ValidatorExecutionException(String validatorId, Throwable cause) {
    super("Validator '" + validatorId + "' threw an exception during execution", cause);
    this.validatorId = validatorId;
  }

  /** Id of the {@link WorkflowValidator} that failed to execute. */
  public String validatorId() {
    return validatorId;
  }
}
