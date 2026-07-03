package io.wogu.core;

import io.wogu.api.Rule;
import io.wogu.api.RuleCategory;
import io.wogu.api.RuleResult;
import io.wogu.api.Severity;
import io.wogu.api.ValidationContext;
import io.wogu.api.ValidatorRunOutcome;
import io.wogu.api.WorkflowValidator;
import java.time.Duration;
import java.util.List;

/** Test double that returns a pre-configured {@link RuleResult}. */
final class FixedResultValidator implements WorkflowValidator {

  private final String id;
  private final RuleResult result;

  FixedResultValidator(String id, RuleResult result) {
    this.id = id;
    this.result = result;
  }

  static FixedResultValidator passing(String validatorId, String ruleId) {
    return new FixedResultValidator(validatorId, RuleResult.of(testRule(ruleId), List.of(), Duration.ofMillis(1)));
  }

  static Rule testRule(String ruleId) {
    return Rule.builder()
        .id(ruleId)
        .title("Test rule " + ruleId)
        .category(RuleCategory.DETERMINISM)
        .severity(Severity.ERROR)
        .engine("Test Engine")
        .sinceVersion("0.1.0")
        .documentationReference("docs/rules/" + ruleId + ".md")
        .build();
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
  public List<Rule> rules() {
    return List.of(result.rule());
  }

  @Override
  public ValidatorRunOutcome validate(ValidationContext context) {
    return ValidatorRunOutcome.of(List.of(result), 0);
  }
}
