package dev.wogu.temporal;

import com.github.javaparser.ast.body.MethodDeclaration;
import dev.wogu.api.CallPathFrame;
import dev.wogu.api.Rule;
import dev.wogu.api.ValidationContext;
import dev.wogu.api.Violation;
import dev.wogu.temporal.callgraph.CallGraphAnalyzer;
import dev.wogu.temporal.callgraph.CallGraphMatch;
import dev.wogu.temporal.callgraph.CallTarget;
import dev.wogu.temporal.callgraph.ContextEntryPoint;
import dev.wogu.temporal.callgraph.ExecutionContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared plumbing every {@link TemporalRule} built on {@link CallGraphAnalyzer} reuses:
 * running a {@link CallTarget} from every workflow class's entry point(s), suppressing any
 * match found in a context the rule doesn't apply to, and converting what's left into
 * {@link Violation}s.
 *
 * <p>Without this, every call-graph-based rule would re-implement the same
 * "for each workflow class, for each entry point, for each target, convert a match into a
 * violation unless its execution context is suppressed" loop, and the same file-path
 * relativization. A new rule of this shape should only need to supply its {@link Rule}
 * metadata, a {@link CallTarget} (or several — see
 * {@link #findViolations(List, CallGraphAnalyzer, List, Rule, ValidationContext, String, String, Predicate, List, Set, Set)}),
 * its message/suggested-fix text, and (optionally) which {@link ExecutionContext}s it is
 * suppressed in, or — the inverse — which one it's confined to. The engine — not the rule —
 * decides whether a found violation is suppressed or out of context: a rule never inspects
 * the AST itself to answer that question.
 */
final class TemporalRuleSupport {

  private TemporalRuleSupport() {}

  /** Convenience overload for a rule with exactly one {@link CallTarget}. */
  static List<Violation> findViolations(
      List<ScannedWorkflowClass> workflowClasses,
      CallGraphAnalyzer callGraph,
      CallTarget target,
      Rule rule,
      ValidationContext context,
      String message,
      String suggestedFix,
      Predicate<MethodDeclaration> activityBoundary,
      List<ContextEntryPoint> contextEntryPoints,
      Set<ExecutionContext> suppressedContexts,
      Set<ExecutionContext> requiredContexts) {
    return findViolations(
        workflowClasses,
        callGraph,
        List.of(target),
        rule,
        context,
        message,
        suggestedFix,
        activityBoundary,
        contextEntryPoints,
        suppressedContexts,
        requiredContexts);
  }

  /**
   * Runs every target in {@code targets} from every workflow class's entry point(s) and
   * returns one {@link Violation} per match found, across all of them, except any match
   * whose {@link CallGraphMatch#executionContext()} is in {@code suppressedContexts}, or —
   * when {@code requiredContexts} is non-empty — any match whose context <em>isn't</em> one
   * of them. Used by rules that flag several distinct call patterns under one rule id (e.g.
   * WG003's several non-deterministic time APIs).
   *
   * @param activityBoundary matches every method that is part of a Temporal Activity
   *     implementation; traversal stops there, since Activity code isn't subject to
   *     workflow replay determinism constraints
   * @param contextEntryPoints calls that establish an {@link ExecutionContext} for their
   *     callback (e.g. {@code Workflow.sideEffect(...)}), shared across every rule's
   *     traversal so each match can be attributed to the context it was found in
   * @param suppressedContexts execution contexts this specific rule does not apply in;
   *     empty means the rule fires regardless of context, exactly as before this existed
   * @param requiredContexts the inverse: execution contexts this rule <em>only</em> applies
   *     in (e.g. WG011 only flagging I/O reachable from inside a
   *     {@code Workflow.sideEffect(...)} callback); empty means no such restriction.
   *     Checked after {@code suppressedContexts}, though in practice a rule uses one or the
   *     other, never both
   */
  static List<Violation> findViolations(
      List<ScannedWorkflowClass> workflowClasses,
      CallGraphAnalyzer callGraph,
      List<CallTarget> targets,
      Rule rule,
      ValidationContext context,
      String message,
      String suggestedFix,
      Predicate<MethodDeclaration> activityBoundary,
      List<ContextEntryPoint> contextEntryPoints,
      Set<ExecutionContext> suppressedContexts,
      Set<ExecutionContext> requiredContexts) {
    List<Violation> violations = new ArrayList<>();
    for (ScannedWorkflowClass workflowClass : workflowClasses) {
      for (MethodDeclaration entryPoint : workflowClass.entryPoints()) {
        for (CallTarget target : targets) {
          for (CallGraphMatch match : callGraph.findCallPaths(entryPoint, target, activityBoundary, contextEntryPoints)) {
            if (suppressedContexts.contains(match.executionContext())) {
              continue;
            }
            if (!requiredContexts.isEmpty() && !requiredContexts.contains(match.executionContext())) {
              continue;
            }
            violations.add(toViolation(rule, context, match, message, suggestedFix));
          }
        }
      }
    }
    return violations;
  }

  private static Violation toViolation(
      Rule rule, ValidationContext context, CallGraphMatch match, String message, String suggestedFix) {
    Path projectDirectory = context.projectDirectory();
    List<CallPathFrame> relativizedPath =
        match.path().stream()
            .map(frame -> new CallPathFrame(frame.displayName(), relativize(projectDirectory, frame.file()), frame.line()))
            .toList();

    return Violation.builder()
        .rule(rule)
        .file(relativize(projectDirectory, match.file()))
        .className(match.containingClassName())
        .line(match.line())
        .message(message)
        .suggestedFix(suggestedFix)
        .callPath(relativizedPath)
        .build();
  }

  /**
   * Renders {@code file} relative to {@code projectDirectory} for readability in reports,
   * falling back to the original path if the two are not comparable (e.g. different
   * filesystem roots).
   */
  private static Path relativize(Path projectDirectory, Path file) {
    try {
      return projectDirectory.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
    } catch (IllegalArgumentException e) {
      return file;
    }
  }
}
