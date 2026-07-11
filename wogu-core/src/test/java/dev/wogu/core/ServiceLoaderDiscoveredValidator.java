package dev.wogu.core;

import dev.wogu.api.Rule;
import dev.wogu.api.RuleCategory;
import dev.wogu.api.RuleResult;
import dev.wogu.api.Severity;
import dev.wogu.api.ValidationContext;
import dev.wogu.api.ValidatorRunOutcome;
import dev.wogu.api.WorkflowValidator;
import java.time.Duration;
import java.util.List;

/**
 * Public validator with a public no-arg constructor, registered via
 * {@code META-INF/services/dev.wogu.api.WorkflowValidator} in test resources, used to prove
 * that {@link ValidationEngine#discover()} finds validators purely through
 * {@link java.util.ServiceLoader} registration.
 */
public final class ServiceLoaderDiscoveredValidator implements WorkflowValidator {

  private static final Rule RULE =
      Rule.builder()
          .id("WG001")
          .title("Discovered purely via ServiceLoader")
          .category(RuleCategory.DETERMINISM)
          .severity(Severity.ERROR)
          .engine("Test Engine")
          .sinceVersion("0.1.0")
          .documentationReference("docs/rules/WG001.md")
          .build();

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
  public List<Rule> rules() {
    return List.of(RULE);
  }

  @Override
  public ValidatorRunOutcome validate(ValidationContext context) {
    return ValidatorRunOutcome.of(List.of(RuleResult.of(RULE, List.of(), Duration.ZERO)), 0);
  }
}
