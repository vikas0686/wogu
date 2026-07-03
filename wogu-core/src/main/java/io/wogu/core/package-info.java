/**
 * The WoGu validation engine.
 *
 * <p>{@link io.wogu.core.ValidationEngine} discovers {@link io.wogu.api.WorkflowValidator}
 * implementations via {@link java.util.ServiceLoader} and runs them against a
 * {@link io.wogu.api.ValidationContext}, producing a {@link io.wogu.api.ValidationSummary}.
 * {@link io.wogu.core.DefaultValidationContext} is the build-tool-agnostic
 * {@link io.wogu.api.ValidationContext} implementation build-tool integrations construct
 * from their own project model.
 */
package io.wogu.core;
