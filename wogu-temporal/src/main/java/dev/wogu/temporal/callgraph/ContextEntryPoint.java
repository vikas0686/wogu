package dev.wogu.temporal.callgraph;

import java.util.Objects;

/**
 * A call site that establishes a new {@link ExecutionContext} for its functional-interface
 * argument (a lambda, in practice) — e.g. "a call matching {@code Workflow.sideEffect(...)}
 * puts everything inside its callback into {@link ExecutionContext#SIDE_EFFECT}".
 *
 * <p>This is the seam that keeps {@link CallGraphAnalyzer} itself free of any specific
 * Temporal API name, mirroring how a {@link CallTarget} lets the engine look for an
 * arbitrary call without knowing what it means: {@code matcher} is reused, off-the-shelf
 * {@link CallTarget} infrastructure (typically a {@link StaticMethodCallTarget}), and
 * {@code context} is the {@link ExecutionContext} its callback runs in. Callers supply a
 * {@code List<ContextEntryPoint>}; the engine has no built-in notion of "sideEffect" at
 * all, so recognizing a future context-changing API is purely a matter of adding another
 * entry, not touching the traversal.
 *
 * @param matcher recognizes the context-establishing call itself (not anything inside its
 *     callback)
 * @param context the execution context that applies within, and is inherited by anything
 *     reachable from, {@code matcher}'s matching call's functional-interface argument
 */
public record ContextEntryPoint(CallTarget matcher, ExecutionContext context) {

  public ContextEntryPoint {
    Objects.requireNonNull(matcher, "matcher");
    Objects.requireNonNull(context, "context");
  }
}
