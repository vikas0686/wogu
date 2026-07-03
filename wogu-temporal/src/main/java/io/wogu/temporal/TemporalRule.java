package io.wogu.temporal;

import io.wogu.api.Rule;
import io.wogu.api.ValidationContext;
import io.wogu.api.Violation;
import io.wogu.temporal.callgraph.CallGraphAnalyzer;
import java.util.List;

/**
 * One rule {@link TemporalWorkflowValidator} evaluates.
 *
 * <p>This is intentionally internal to this module, not part of {@code wogu-api}:
 * {@link io.wogu.api.WorkflowValidator} is the public extension point other engine
 * modules implement, while {@code TemporalRule} is how this one validator organizes
 * evaluating several rules in a single pass over the project's parsed source and shared
 * {@link CallGraphAnalyzer}, without each rule re-scanning the source itself.
 *
 * <p>To add a new Temporal rule: implement this interface and add an instance to
 * {@link TemporalWorkflowValidator}'s rule list. No other class changes.
 */
interface TemporalRule {

  /** This rule's metadata, shown in reports and console output. */
  Rule metadata();

  /**
   * Evaluates this rule against every scanned workflow class.
   *
   * @param context the project being validated, e.g. for relativizing file paths
   * @param workflowClasses every workflow implementation class found in the project
   * @param callGraph shared call-graph engine, already built from the same parsed source
   * @return violations found, if any
   */
  List<Violation> evaluate(ValidationContext context, List<ScannedWorkflowClass> workflowClasses, CallGraphAnalyzer callGraph);
}
