package io.wogu.temporal;

import io.wogu.api.Rule;
import io.wogu.api.RuleCategory;
import io.wogu.api.Severity;
import io.wogu.api.ValidationContext;
import io.wogu.api.Violation;
import io.wogu.temporal.callgraph.CallGraphAnalyzer;
import io.wogu.temporal.callgraph.CallTarget;
import io.wogu.temporal.callgraph.StaticMethodCallTarget;
import java.util.List;

/**
 * WG003: flags reads of the current wall-clock time — {@code System.currentTimeMillis()},
 * {@code Instant.now()}, {@code LocalDate.now()}, {@code LocalDateTime.now()},
 * {@code OffsetDateTime.now()}, {@code ZonedDateTime.now()}, {@code Clock.systemUTC()},
 * and {@code Clock.systemDefaultZone()} — reachable from a Temporal workflow entry point,
 * however many method calls deep.
 *
 * <p>Every one of these APIs returns the current system time, which is not recorded in
 * workflow history. Temporal replays workflow code from that history to reconstruct
 * state, so reading the current time during replay can return a different value than the
 * original execution did, diverging workflow execution from what actually happened.
 * {@code io.temporal.workflow.Workflow.currentTimeMillis()} (and Temporal's other
 * replay-safe time accessors) read the time as recorded in history instead, and are this
 * rule's suggested fix. Calls to {@code Workflow.currentTimeMillis()} itself are a
 * different method on a different class and are correctly never flagged.
 */
final class NonDeterministicTimeApiRule implements TemporalRule {

  static final String ID = "WG003";

  private static final Rule METADATA =
      Rule.builder()
          .id(ID)
          .title("Non-deterministic Time APIs inside Workflow")
          .category(RuleCategory.DETERMINISM)
          .severity(Severity.ERROR)
          .engine("Temporal Java SDK")
          .sinceVersion("0.2.0")
          .documentationReference("docs/rules/WG003.md")
          .build();

  private static final String MESSAGE =
      "This API returns the current wall-clock time. Temporal workflows replay workflow "
          + "code from event history, so reading the current system time during replay may "
          + "produce a different value than the original execution and cause workflow "
          + "execution to diverge from recorded history.";

  private static final String SUGGESTED_FIX =
      "Use Workflow.currentTimeMillis() (or another Temporal-provided deterministic time "
          + "API) instead. It returns the time as recorded in workflow history, so it is "
          + "stable across replay.";

  private static final List<CallTarget> TARGETS =
      List.of(
          new StaticMethodCallTarget("java.lang.System", "currentTimeMillis"),
          new StaticMethodCallTarget("java.time.Instant", "now"),
          new StaticMethodCallTarget("java.time.LocalDate", "now"),
          new StaticMethodCallTarget("java.time.LocalDateTime", "now"),
          new StaticMethodCallTarget("java.time.OffsetDateTime", "now"),
          new StaticMethodCallTarget("java.time.ZonedDateTime", "now"),
          new StaticMethodCallTarget("java.time.Clock", "systemUTC"),
          new StaticMethodCallTarget("java.time.Clock", "systemDefaultZone"));

  @Override
  public Rule metadata() {
    return METADATA;
  }

  @Override
  public List<Violation> evaluate(
      ValidationContext context, List<ScannedWorkflowClass> workflowClasses, CallGraphAnalyzer callGraph) {
    return TemporalRuleSupport.findViolations(
        workflowClasses, callGraph, TARGETS, METADATA, context, MESSAGE, SUGGESTED_FIX);
  }
}
