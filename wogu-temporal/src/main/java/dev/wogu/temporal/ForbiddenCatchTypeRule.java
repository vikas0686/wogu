package dev.wogu.temporal;

import com.github.javaparser.ast.body.MethodDeclaration;
import dev.wogu.api.Rule;
import dev.wogu.api.ValidationContext;
import dev.wogu.api.Violation;
import dev.wogu.temporal.callgraph.CallGraphAnalyzer;
import dev.wogu.temporal.callgraph.CallGraphMatch;
import dev.wogu.temporal.callgraph.ContextEntryPoint;
import dev.wogu.temporal.callgraph.ExecutionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The {@code forbidden-catch-type} declarative rule type: flags a {@code catch} clause,
 * reachable from a workflow entry point, whose caught type matches one of a
 * {@link RuleDefinition}'s {@code catchTypes}.
 *
 * <p>This is the generic engine behind WG013 ({@code catch (Throwable)}/{@code catch
 * (Error)}) and any future "flag catching this exception type" rule — none of which need a
 * dedicated Java class. Adding one is purely a matter of adding a YAML definition with
 * {@code type: forbidden-catch-type} and a {@code catchTypes} list under
 * {@code src/main/resources/rules}; this class reads it, calls
 * {@link CallGraphAnalyzer#findCaughtTypeMatches}, and reuses
 * {@link TemporalRuleSupport#toViolations} for the same suppressed/required-context
 * filtering and violation-building {@link ForbiddenMethodRule} uses for its own shape — the
 * only real difference is which {@link CallGraphAnalyzer} method supplies the matches,
 * since a {@code catch} clause's type isn't a method call or constructor a
 * {@link dev.wogu.temporal.callgraph.CallTarget} can match.
 */
final class ForbiddenCatchTypeRule implements TemporalRule {

  private final Rule metadata;
  private final List<String> catchTypes;
  private final String message;
  private final String suggestedFix;
  private final Set<ExecutionContext> suppressedContexts;
  private final Set<ExecutionContext> requiredContexts;

  ForbiddenCatchTypeRule(RuleDefinition definition) {
    this.metadata = definition.toRule();
    if (definition.catchTypes().isEmpty()) {
      throw new IllegalArgumentException(
          "Rule '" + definition.id() + "' of type 'forbidden-catch-type' requires at least one entry in "
              + "'catchTypes'");
    }
    this.catchTypes = definition.catchTypes();
    this.message = definition.description();
    this.suggestedFix = definition.replacement();
    this.suppressedContexts =
        definition.suppressedContexts().stream().map(ForbiddenMethodRule::toExecutionContext).collect(Collectors.toUnmodifiableSet());
    this.requiredContexts =
        definition.requiredContexts().stream().map(ForbiddenMethodRule::toExecutionContext).collect(Collectors.toUnmodifiableSet());
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
    List<CallGraphMatch> matches = new ArrayList<>();
    for (ScannedWorkflowClass workflowClass : workflowClasses) {
      for (MethodDeclaration entryPoint : workflowClass.entryPoints()) {
        matches.addAll(callGraph.findCaughtTypeMatches(entryPoint, catchTypes, activityBoundary, contextEntryPoints));
      }
    }
    return TemporalRuleSupport.toViolations(
        matches, metadata, context, message, suggestedFix, suppressedContexts, requiredContexts);
  }
}
