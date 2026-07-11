package dev.wogu.temporal;

import com.github.javaparser.ast.body.MethodDeclaration;
import dev.wogu.api.Rule;
import dev.wogu.api.ValidationContext;
import dev.wogu.api.Violation;
import dev.wogu.temporal.callgraph.CallGraphAnalyzer;
import dev.wogu.temporal.callgraph.CallTarget;
import dev.wogu.temporal.callgraph.ContextEntryPoint;
import dev.wogu.temporal.callgraph.ValueBasedEqualityArgumentTarget;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The {@code mutable-side-effect-equality} declarative rule type: flags a call to the
 * single method named in a {@link RuleDefinition}'s {@code methods} list whose
 * {@code Class<T>} argument at {@code valueTypeArgumentIndex} names a type relying on
 * inherited, identity-based {@code equals()}.
 *
 * <p>This is the generic engine behind WG012 ({@code Workflow.mutableSideEffect()}'s value
 * type) and any future "this argument's resolved type must have real equality" rule of the
 * same shape — none of which need a dedicated Java class. Adding one is purely a matter of
 * adding a YAML definition with {@code type: mutable-side-effect-equality},
 * {@code methods: [the one method]}, and {@code valueTypeArgumentIndex} under
 * {@code src/main/resources/rules}; this class builds the matching
 * {@link ValueBasedEqualityArgumentTarget} and reuses {@link TemporalRuleSupport} for the
 * actual call-graph traversal and violation building, exactly like {@link ForbiddenMethodRule}
 * does for its own shape.
 */
final class MutableSideEffectEqualityRule implements TemporalRule {

  private final Rule metadata;
  private final CallTarget target;
  private final String message;
  private final String suggestedFix;

  MutableSideEffectEqualityRule(RuleDefinition definition) {
    this.metadata = definition.toRule();
    if (definition.methods().size() != 1) {
      throw new IllegalArgumentException(
          "Rule '" + definition.id() + "' of type 'mutable-side-effect-equality' must declare exactly one "
              + "method, found: " + definition.methods());
    }
    if (definition.valueTypeArgumentIndex() == null) {
      throw new IllegalArgumentException(
          "Rule '" + definition.id() + "' of type 'mutable-side-effect-equality' requires "
              + "'valueTypeArgumentIndex'");
    }
    this.target =
        new ValueBasedEqualityArgumentTarget(definition.methods().get(0), definition.valueTypeArgumentIndex());
    this.message = definition.description();
    this.suggestedFix = definition.replacement();
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
        target,
        metadata,
        context,
        message,
        suggestedFix,
        activityBoundary,
        contextEntryPoints,
        Set.of(),
        Set.of());
  }
}
