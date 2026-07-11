/**
 * The WoGu validation engine.
 *
 * <p>{@link dev.wogu.core.ValidationEngine} discovers {@link dev.wogu.api.WorkflowValidator}
 * implementations via {@link java.util.ServiceLoader} and runs them against a
 * {@link dev.wogu.api.ValidationContext}, producing a {@link dev.wogu.api.ValidationSummary}.
 * {@link dev.wogu.core.DefaultValidationContext} is the build-tool-agnostic
 * {@link dev.wogu.api.ValidationContext} implementation build-tool integrations construct
 * from their own project model.
 */
package dev.wogu.core;
