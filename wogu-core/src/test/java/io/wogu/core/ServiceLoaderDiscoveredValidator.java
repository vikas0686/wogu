package io.wogu.core;

import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationResult;
import io.wogu.api.WorkflowValidator;
import java.time.Duration;
import java.util.List;

/**
 * Public validator with a public no-arg constructor, registered via
 * {@code META-INF/services/io.wogu.api.WorkflowValidator} in test resources, used to prove
 * that {@link ValidationEngine#discover()} finds validators purely through
 * {@link java.util.ServiceLoader} registration.
 */
public final class ServiceLoaderDiscoveredValidator implements WorkflowValidator {

  public ServiceLoaderDiscoveredValidator() {}

  @Override
  public String id() {
    return "service-loader-discovered";
  }

  @Override
  public String description() {
    return "Discovered purely via ServiceLoader for engine tests";
  }

  @Override
  public ValidationResult validate(ValidationContext context) {
    return ValidationResult.of(id(), List.of(), Duration.ZERO);
  }
}
