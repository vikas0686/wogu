package io.wogu.temporal;

import com.github.javaparser.ast.CompilationUnit;
import io.wogu.api.Rule;
import io.wogu.api.RuleResult;
import io.wogu.api.ValidationContext;
import io.wogu.api.ValidatorRunOutcome;
import io.wogu.api.Violation;
import io.wogu.api.WorkflowValidator;
import io.wogu.temporal.callgraph.CallGraphAnalyzer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * WoGu's Temporal Java SDK validator, registered via {@code META-INF/services} as the
 * {@link WorkflowValidator} implementation for this module.
 *
 * <p>Parses the project's source once, scans it for workflow implementation classes once,
 * builds one shared {@link CallGraphAnalyzer}, and then evaluates every {@link TemporalRule}
 * against that shared state. Most rules today are declarative: {@link RuleRegistry} loads
 * one {@link TemporalRule} per YAML definition under {@code src/main/resources/rules}
 * (currently WG001–WG003, all {@code forbidden-method} rules), so adding one of those
 * requires no change here at all. {@link #CUSTOM_RULES} is the (currently empty) list for
 * future hand-written {@link CustomRule} subclasses that need real analysis logic.
 */
public final class TemporalWorkflowValidator implements WorkflowValidator {

  /** Hand-written rules that can't be expressed declaratively — none yet. */
  private static final List<TemporalRule> CUSTOM_RULES = List.of();

  private final List<TemporalRule> rules;
  private final WorkflowImplementationScanner scanner = new WorkflowImplementationScanner();

  public TemporalWorkflowValidator() {
    List<TemporalRule> declarativeRules = RuleRegistry.loadDeclarativeRules(getClass().getClassLoader());
    this.rules = Stream.concat(declarativeRules.stream(), CUSTOM_RULES.stream()).toList();
  }

  @Override
  public String id() {
    return "wogu-temporal";
  }

  @Override
  public String description() {
    return "Temporal Java SDK workflow determinism and best-practice rules.";
  }

  @Override
  public List<Rule> rules() {
    return rules.stream().map(TemporalRule::metadata).toList();
  }

  @Override
  public ValidatorRunOutcome validate(ValidationContext context) {
    List<CompilationUnit> units = SourceRootParser.parse(context.sourceRoots());
    List<ScannedWorkflowClass> workflowClasses = scanner.scan(units);
    CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

    List<RuleResult> ruleResults = new ArrayList<>(rules.size());
    for (TemporalRule rule : rules) {
      Instant start = Instant.now();
      List<Violation> violations = rule.evaluate(context, workflowClasses, callGraph);
      Duration elapsed = Duration.between(start, Instant.now());
      ruleResults.add(RuleResult.of(rule.metadata(), violations, elapsed));
    }

    return ValidatorRunOutcome.of(ruleResults, workflowClasses.size());
  }
}
