package io.wogu.temporal;

import io.wogu.api.Rule;
import io.wogu.api.ValidationContext;
import io.wogu.api.Violation;
import io.wogu.temporal.callgraph.CallGraphAnalyzer;
import io.wogu.temporal.callgraph.CallTarget;
import io.wogu.temporal.callgraph.StaticMethodCallTarget;
import java.util.List;

/**
 * The {@code forbidden-method} declarative rule type: flags every reachable call to one
 * or more specific static methods, listed in a {@link RuleDefinition}'s {@code methods}.
 *
 * <p>This is the generic engine behind WG001 ({@code UUID.randomUUID()}), WG002
 * ({@code Thread.sleep()}), WG003 (several non-deterministic time APIs), and any future
 * "flag this method call" rule — none of which need a dedicated Java class anymore.
 * Adding one is purely a matter of adding a YAML definition with
 * {@code type: forbidden-method} under {@code src/main/resources/rules}; this class reads
 * its {@code methods} list, builds a {@link StaticMethodCallTarget} per entry, and reuses
 * {@link TemporalRuleSupport} for the actual call-graph traversal and violation building —
 * exactly the same infrastructure the three original hand-written rules used.
 */
final class ForbiddenMethodRule implements TemporalRule {

  private final Rule metadata;
  private final List<CallTarget> targets;
  private final String message;
  private final String suggestedFix;

  ForbiddenMethodRule(RuleDefinition definition) {
    this.metadata = definition.toRule();
    this.targets = definition.methods().stream().map(ForbiddenMethodRule::toCallTarget).toList();
    this.message = definition.description();
    this.suggestedFix = definition.replacement();
  }

  @Override
  public Rule metadata() {
    return metadata;
  }

  @Override
  public List<Violation> evaluate(
      ValidationContext context, List<ScannedWorkflowClass> workflowClasses, CallGraphAnalyzer callGraph) {
    return TemporalRuleSupport.findViolations(workflowClasses, callGraph, targets, metadata, context, message, suggestedFix);
  }

  /**
   * Splits a fully qualified {@code Class.method} reference (e.g.
   * {@code "java.util.UUID.randomUUID"}) at its last dot into a class name and a method
   * name, matching how {@link StaticMethodCallTarget} identifies the method it matches.
   */
  private static CallTarget toCallTarget(String qualifiedMethodReference) {
    int lastDot = qualifiedMethodReference.lastIndexOf('.');
    if (lastDot < 0) {
      throw new IllegalArgumentException(
          "Not a fully qualified Class.method reference: '" + qualifiedMethodReference + "'");
    }
    String className = qualifiedMethodReference.substring(0, lastDot);
    String methodName = qualifiedMethodReference.substring(lastDot + 1);
    return new StaticMethodCallTarget(className, methodName);
  }
}
