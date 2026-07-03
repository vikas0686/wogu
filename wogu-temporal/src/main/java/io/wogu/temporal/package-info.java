/**
 * Temporal Java SDK rules for WoGu.
 *
 * <p>{@link io.wogu.temporal.TemporalWorkflowValidator} is registered via
 * {@code META-INF/services/io.wogu.api.WorkflowValidator} so that {@code wogu-core}'s
 * engine discovers it automatically. It evaluates every {@code TemporalRule} in this
 * module — currently just WG001 ({@code UuidRandomUuidRule}) — sharing one parse of the
 * project's source, one {@link io.wogu.temporal.WorkflowImplementationScanner} pass, and
 * one {@link io.wogu.temporal.callgraph.CallGraphAnalyzer} across all of them.
 *
 * <p>To add another Temporal rule: implement the package-private {@code TemporalRule}
 * interface and add an instance to {@code TemporalWorkflowValidator}'s rule list. To add
 * an entirely new engine (Conductor, Camunda, Airflow, ...), create a new module
 * depending only on {@code wogu-api} and follow the same
 * {@link io.wogu.api.WorkflowValidator} + {@code META-INF/services} pattern — neither
 * {@code wogu-core} nor this module needs to change either way.
 */
package io.wogu.temporal;
