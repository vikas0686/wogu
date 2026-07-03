/**
 * Temporal Java SDK validators for WoGu.
 *
 * <p>{@link io.wogu.temporal.UUIDRandomValidator} is the first validator, registered via
 * {@code META-INF/services/io.wogu.api.WorkflowValidator} so that {@code wogu-core}'s
 * engine discovers it automatically. {@link io.wogu.temporal.WorkflowImplementationScanner}
 * is shared infrastructure for identifying Temporal workflow implementation classes,
 * reusable by future validators in this module.
 *
 * <p>To add another Temporal validator: implement {@link io.wogu.api.WorkflowValidator},
 * add its fully qualified name as a new line in the
 * {@code META-INF/services/io.wogu.api.WorkflowValidator} file, and nothing else — no
 * other class in this module or in {@code wogu-core} needs to change.
 */
package io.wogu.temporal;
