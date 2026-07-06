package io.wogu.temporal.callgraph;

/**
 * The semantic execution context a {@link CallGraphAnalyzer} traversal is currently
 * inside, as opposed to the mechanical "which method is this" that {@link CallPathFrame}
 * already tracks. Two calls to the exact same API can have different determinism
 * implications depending on which Temporal execution context they run in — e.g.
 * {@code UUID.randomUUID()} is a replay hazard in ordinary workflow code, but not inside
 * {@code Workflow.sideEffect(...)}, whose whole purpose is to run non-deterministic code
 * exactly once and record its result in workflow history.
 *
 * <p>This is a plain, growable enum, not a redesign point: adding a future context (e.g.
 * {@code ACTIVITY}, {@code SIGNAL_HANDLER}, {@code UPDATE_HANDLER}) is adding one constant
 * here, the same way {@code RuleCategory} and {@code Severity} already grow by adding
 * constants. Nothing about how {@link CallGraphAnalyzer} or {@link ContextEntryPoint}
 * work needs to change to support a new one.
 */
public enum ExecutionContext {

  /** Ordinary workflow code: the default, and the only context that existed before this. */
  NORMAL_WORKFLOW,

  /**
   * Inside the callback passed to {@code Workflow.sideEffect(...)}: Temporal executes it
   * exactly once and replays its recorded result thereafter, so code that would otherwise
   * be a determinism violation is safe here by design.
   */
  SIDE_EFFECT,

  /**
   * Inside the callback passed to {@code Workflow.mutableSideEffect(...)}: the same
   * once-and-recorded execution guarantee as {@link #SIDE_EFFECT}, kept as a distinct
   * context (rather than folded into it) since a future rule may reasonably want to treat
   * the two differently.
   */
  MUTABLE_SIDE_EFFECT
}
