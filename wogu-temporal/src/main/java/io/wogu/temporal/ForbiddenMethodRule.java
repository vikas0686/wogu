package io.wogu.temporal;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.wogu.api.Rule;
import io.wogu.api.ValidationContext;
import io.wogu.api.Violation;
import io.wogu.temporal.callgraph.CallGraphAnalyzer;
import io.wogu.temporal.callgraph.CallTarget;
import io.wogu.temporal.callgraph.ConstructorCallTarget;
import io.wogu.temporal.callgraph.ContextEntryPoint;
import io.wogu.temporal.callgraph.ExecutionContext;
import io.wogu.temporal.callgraph.StaticMethodCallTarget;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@code forbidden-method} declarative rule type: flags every reachable call to one
 * or more specific methods (via {@code methods}) and/or construction of one or more
 * specific classes (via {@code constructors}), listed in a {@link RuleDefinition}, except
 * where reachable only from a suppressed {@link ExecutionContext} (via
 * {@code suppressedContexts}).
 *
 * <p>This is the generic engine behind WG001 ({@code UUID.randomUUID()}) through WG010
 * (thread/executor creation), and any future "flag this method call or this constructor"
 * rule — none of which need a dedicated Java class. Adding one is purely a matter of
 * adding a YAML definition with {@code type: forbidden-method} under
 * {@code src/main/resources/rules}; this class reads its {@code methods}/{@code constructors}
 * lists, builds the matching {@link CallTarget}s, reads its {@code suppressedContexts}
 * list into a {@code Set<ExecutionContext>}, and reuses {@link TemporalRuleSupport} for
 * the actual call-graph traversal, suppression, and violation building.
 */
final class ForbiddenMethodRule implements TemporalRule {

  private final Rule metadata;
  private final List<CallTarget> targets;
  private final String message;
  private final String suggestedFix;
  private final Set<ExecutionContext> suppressedContexts;

  ForbiddenMethodRule(RuleDefinition definition) {
    this.metadata = definition.toRule();
    this.targets =
        Stream.concat(
                definition.methods().stream().map(ForbiddenMethodRule::toMethodCallTarget),
                definition.constructors().stream().map(ConstructorCallTarget::new))
            .toList();
    this.message = definition.description();
    this.suggestedFix = definition.replacement();
    this.suppressedContexts =
        definition.suppressedContexts().stream().map(ForbiddenMethodRule::toExecutionContext).collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Rule metadata() {
    return metadata;
  }

  @Override
  public List<Violation> evaluate(
      ValidationContext context,
      List<ScannedWorkflowClass> workflowClasses,
      CallGraphAnalyzer callGraph,
      Predicate<MethodDeclaration> activityBoundary,
      List<ContextEntryPoint> contextEntryPoints) {
    return TemporalRuleSupport.findViolations(
        workflowClasses,
        callGraph,
        targets,
        metadata,
        context,
        message,
        suggestedFix,
        activityBoundary,
        contextEntryPoints,
        suppressedContexts);
  }

  /**
   * Splits a fully qualified {@code Class.method} reference (e.g.
   * {@code "java.util.UUID.randomUUID"}) at its last dot into a class name and a method
   * name, matching how {@link StaticMethodCallTarget} identifies the method it matches.
   */
  private static CallTarget toMethodCallTarget(String qualifiedMethodReference) {
    int lastDot = qualifiedMethodReference.lastIndexOf('.');
    if (lastDot < 0) {
      throw new IllegalArgumentException(
          "Not a fully qualified Class.method reference: '" + qualifiedMethodReference + "'");
    }
    String className = qualifiedMethodReference.substring(0, lastDot);
    String methodName = qualifiedMethodReference.substring(lastDot + 1);
    return new StaticMethodCallTarget(className, methodName);
  }

  private static ExecutionContext toExecutionContext(String name) {
    try {
      return ExecutionContext.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Not a known ExecutionContext: '" + name + "'. Known values: " + List.of(ExecutionContext.values()), e);
    }
  }
}
