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

/**
 * WoGu's Temporal Java SDK validator, registered via {@code META-INF/services} as the
 * {@link WorkflowValidator} implementation for this module.
 *
 * <p>Parses the project's source once, scans it for workflow implementation classes once,
 * builds one shared {@link CallGraphAnalyzer}, and then evaluates every {@link TemporalRule}
 * against that shared state — adding a new rule means adding it to {@link #RULES}, not
 * re-implementing scanning or traversal.
 */
public final class TemporalWorkflowValidator implements WorkflowValidator {

  private static final List<TemporalRule> RULES =
      List.of(new UuidRandomUuidRule(), new ThreadSleepRule(), new NonDeterministicTimeApiRule());

  private final WorkflowImplementationScanner scanner = new WorkflowImplementationScanner();

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
    return RULES.stream().map(TemporalRule::metadata).toList();
  }

  @Override
  public ValidatorRunOutcome validate(ValidationContext context) {
    List<CompilationUnit> units = SourceRootParser.parse(context.sourceRoots());
    List<ScannedWorkflowClass> workflowClasses = scanner.scan(units);
    CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

    List<RuleResult> ruleResults = new ArrayList<>(RULES.size());
    for (TemporalRule rule : RULES) {
      Instant start = Instant.now();
      List<Violation> violations = rule.evaluate(context, workflowClasses, callGraph);
      Duration elapsed = Duration.between(start, Instant.now());
      ruleResults.add(RuleResult.of(rule.metadata(), violations, elapsed));
    }

    return ValidatorRunOutcome.of(ruleResults, workflowClasses.size());
  }
}
