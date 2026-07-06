package io.wogu.temporal;

import io.wogu.temporal.callgraph.ContextEntryPoint;
import io.wogu.temporal.callgraph.ExecutionContext;
import io.wogu.temporal.callgraph.StaticMethodCallTarget;
import java.util.List;

/**
 * The one place that knows which Temporal SDK calls change the execution context for
 * their callback: {@code Workflow.sideEffect(...)} and {@code Workflow.mutableSideEffect(...)}
 * today. {@link io.wogu.temporal.callgraph.CallGraphAnalyzer} itself has no idea these
 * methods exist — it is handed the resulting {@link ContextEntryPoint} list and applies it
 * generically, the same way {@link ActivityAwareness} supplies a traversal boundary
 * without the analyzer knowing what an Activity is.
 *
 * <p>Each entry point is matched with the exact same {@link StaticMethodCallTarget} every
 * {@code forbidden-method} rule already uses, so recognizing
 * {@code Workflow.sideEffect(...)} needed no new AST-matching logic, only a new instance
 * of an existing class. Adding a future context-establishing API (should one ever exist)
 * is adding one more entry here; nothing else changes.
 */
final class TemporalExecutionContexts {

  private static final String WORKFLOW_CLASS = "io.temporal.workflow.Workflow";

  private static final List<ContextEntryPoint> ENTRY_POINTS =
      List.of(
          new ContextEntryPoint(new StaticMethodCallTarget(WORKFLOW_CLASS, "sideEffect"), ExecutionContext.SIDE_EFFECT),
          new ContextEntryPoint(
              new StaticMethodCallTarget(WORKFLOW_CLASS, "mutableSideEffect"), ExecutionContext.MUTABLE_SIDE_EFFECT));

  private TemporalExecutionContexts() {}

  /**
   * The context-establishing calls every Temporal rule's traversal should recognize,
   * computed once per {@code validate()} run and shared across every rule the same way
   * {@link ActivityAwareness#activityBoundary} is.
   */
  static List<ContextEntryPoint> entryPoints() {
    return ENTRY_POINTS;
  }
}
