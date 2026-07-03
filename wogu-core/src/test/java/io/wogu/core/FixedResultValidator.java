package io.wogu.core;

import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationResult;
import io.wogu.api.WorkflowValidator;
import java.time.Duration;
import java.util.List;

/** Test double that returns a pre-configured {@link ValidationResult}. */
final class FixedResultValidator implements WorkflowValidator {

  private final String id;
  private final ValidationResult result;

  FixedResultValidator(String id, ValidationResult result) {
    this.id = id;
    this.result = result;
  }

  static FixedResultValidator passing(String id) {
    return new FixedResultValidator(id, ValidationResult.of(id, List.of(), Duration.ofMillis(1)));
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String description() {
    return "Test validator returning a fixed result";
  }

  @Override
  public ValidationResult validate(ValidationContext context) {
    return result;
  }
}
