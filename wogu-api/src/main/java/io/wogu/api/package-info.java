/**
 * Public SPI for WoGu (Workflow Guard).
 *
 * <p>This package defines the {@link io.wogu.api.WorkflowValidator} extension point and
 * the immutable model types validators and build-tool integrations exchange:
 * {@link io.wogu.api.ValidationContext}, {@link io.wogu.api.ValidationResult},
 * {@link io.wogu.api.ValidationSummary}, {@link io.wogu.api.Violation}, and
 * {@link io.wogu.api.Severity}.
 *
 * <p>This module has no dependency on any workflow engine (Temporal, Conductor, ...), any
 * parser, or any build tool. Engine-specific validators (e.g. {@code wogu-temporal})
 * depend only on this module, which keeps them pluggable without ever requiring a change
 * to the core engine.
 */
package io.wogu.api;
