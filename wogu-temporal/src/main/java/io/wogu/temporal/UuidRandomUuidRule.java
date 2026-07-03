package io.wogu.temporal;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.wogu.api.CallPathFrame;
import io.wogu.api.Rule;
import io.wogu.api.RuleCategory;
import io.wogu.api.Severity;
import io.wogu.api.ValidationContext;
import io.wogu.api.Violation;
import io.wogu.temporal.callgraph.CallGraphAnalyzer;
import io.wogu.temporal.callgraph.CallGraphMatch;
import io.wogu.temporal.callgraph.CallTarget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WG001: flags {@code UUID.randomUUID()} reachable from a Temporal workflow entry point,
 * however many method calls deep — not just direct usage inside the workflow
 * implementation class itself.
 *
 * <p>Temporal replays workflow code from event history to reconstruct state.
 * {@code java.util.UUID.randomUUID()} is not seeded from that history, so a replay
 * produces a different value than the original execution did, which can diverge workflow
 * execution from recorded history and raise a {@code NonDeterministicException}.
 * {@code io.temporal.workflow.Workflow.randomUUID()} is deterministic across replay and is
 * this rule's suggested fix.
 */
final class UuidRandomUuidRule implements TemporalRule {

  static final String ID = "WG001";

  private static final Rule METADATA =
      Rule.builder()
          .id(ID)
          .title("UUID.randomUUID() inside Workflow")
          .category(RuleCategory.DETERMINISM)
          .severity(Severity.ERROR)
          .engine("Temporal Java SDK")
          .sinceVersion("0.1.0")
          .documentationReference("docs/rules/WG001.md")
          .build();

  private static final String MESSAGE =
      "UUID.randomUUID() generates a different value every execution. Temporal workflows "
          + "replay workflow code from event history, so using UUID.randomUUID() produces a "
          + "new value during replay, which can cause workflow execution to diverge from "
          + "recorded history and result in a NonDeterministicException.";

  private static final String SUGGESTED_FIX =
      "Use Workflow.randomUUID() instead. It is backed by a deterministic random number "
          + "generator seeded from workflow history, so it produces the same value on every "
          + "replay.";

  private final CallTarget target = new UuidRandomUuidCallTarget();

  @Override
  public Rule metadata() {
    return METADATA;
  }

  @Override
  public List<Violation> evaluate(
      ValidationContext context, List<ScannedWorkflowClass> workflowClasses, CallGraphAnalyzer callGraph) {
    List<Violation> violations = new ArrayList<>();
    for (ScannedWorkflowClass workflowClass : workflowClasses) {
      for (MethodDeclaration entryPoint : workflowClass.entryPoints()) {
        for (CallGraphMatch match : callGraph.findCallPaths(entryPoint, target)) {
          violations.add(toViolation(context, match));
        }
      }
    }
    return violations;
  }

  private static Violation toViolation(ValidationContext context, CallGraphMatch match) {
    Path projectDirectory = context.projectDirectory();
    List<CallPathFrame> relativizedPath =
        match.path().stream()
            .map(frame -> new CallPathFrame(frame.displayName(), relativize(projectDirectory, frame.file()), frame.line()))
            .toList();

    return Violation.builder()
        .rule(METADATA)
        .file(relativize(projectDirectory, match.file()))
        .className(match.containingClassName())
        .line(match.line())
        .message(MESSAGE)
        .suggestedFix(SUGGESTED_FIX)
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
