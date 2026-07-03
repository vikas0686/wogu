package io.wogu.temporal;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.wogu.api.CallPathFrame;
import io.wogu.api.Rule;
import io.wogu.api.ValidationContext;
import io.wogu.api.Violation;
import io.wogu.temporal.callgraph.CallGraphAnalyzer;
import io.wogu.temporal.callgraph.CallGraphMatch;
import io.wogu.temporal.callgraph.CallTarget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing every {@link TemporalRule} built on {@link CallGraphAnalyzer} reuses:
 * running a {@link CallTarget} from every workflow class's entry point(s) and converting
 * each {@link CallGraphMatch} into a {@link Violation}.
 *
 * <p>Without this, every call-graph-based rule would re-implement the same
 * "for each workflow class, for each entry point, for each match, build a violation"
 * loop and the same file-path relativization. A new rule of this shape should only need
 * to supply its {@link Rule} metadata, a {@link CallTarget} (or several — see
 * {@link #findViolations(List, CallGraphAnalyzer, List, Rule, ValidationContext, String, String)}),
 * and its message/suggested-fix text.
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
      String suggestedFix) {
    return findViolations(workflowClasses, callGraph, List.of(target), rule, context, message, suggestedFix);
  }

  /**
   * Runs every target in {@code targets} from every workflow class's entry point(s) and
   * returns one {@link Violation} per match found, across all of them. Used by rules that
   * flag several distinct call patterns under one rule id (e.g. WG003's several
   * non-deterministic time APIs).
   */
  static List<Violation> findViolations(
      List<ScannedWorkflowClass> workflowClasses,
      CallGraphAnalyzer callGraph,
      List<CallTarget> targets,
      Rule rule,
      ValidationContext context,
      String message,
      String suggestedFix) {
    List<Violation> violations = new ArrayList<>();
    for (ScannedWorkflowClass workflowClass : workflowClasses) {
      for (MethodDeclaration entryPoint : workflowClass.entryPoints()) {
        for (CallTarget target : targets) {
          for (CallGraphMatch match : callGraph.findCallPaths(entryPoint, target)) {
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
