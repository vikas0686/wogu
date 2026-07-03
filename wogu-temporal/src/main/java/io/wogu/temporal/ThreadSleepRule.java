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
 * WG002: flags {@code Thread.sleep(...)} reachable from a Temporal workflow entry point,
 * however many method calls deep.
 *
 * <p>{@code Thread.sleep()} blocks the worker thread executing the workflow. Temporal's
 * programming model expects workflow code to yield control via durable timers rather than
 * blocking threads directly; doing so ties up a worker thread for the sleep duration and
 * can lead to replay issues, since a blocked thread is not how Temporal reconstructs
 * elapsed time from history. {@code io.temporal.workflow.Workflow.sleep(Duration)} is the
 * deterministic, non-blocking equivalent and is this rule's suggested fix.
 */
final class ThreadSleepRule implements TemporalRule {

  static final String ID = "WG002";

  private static final Rule METADATA =
      Rule.builder()
          .id(ID)
          .title("Thread.sleep() inside Workflow")
          .category(RuleCategory.DETERMINISM)
          .severity(Severity.ERROR)
          .engine("Temporal Java SDK")
          .sinceVersion("0.2.0")
          .documentationReference("docs/rules/WG002.md")
          .build();

  private static final String MESSAGE =
      "Thread.sleep() blocks the current worker thread. Temporal workflows must use "
          + "virtual timers instead of blocking threads; blocking a worker thread inside "
          + "workflow code breaks Temporal's programming model and can lead to replay issues.";

  private static final String SUGGESTED_FIX =
      "Use Workflow.sleep(Duration) instead of Thread.sleep(). Workflow.sleep() is a "
          + "durable timer that does not block the worker thread and replays deterministically.";

  private final CallTarget target = new StaticMethodCallTarget("java.lang.Thread", "sleep");

  @Override
  public Rule metadata() {
    return METADATA;
  }

  @Override
  public List<Violation> evaluate(
      ValidationContext context, List<ScannedWorkflowClass> workflowClasses, CallGraphAnalyzer callGraph) {
    return TemporalRuleSupport.findViolations(workflowClasses, callGraph, target, METADATA, context, MESSAGE, SUGGESTED_FIX);
  }
}
