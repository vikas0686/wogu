/**
 * Public SPI for WoGu (Workflow Guard).
 *
 * <p>This package defines the {@link io.wogu.api.WorkflowValidator} extension point, the
 * {@link io.wogu.api.Rule} metadata and {@link io.wogu.api.RuleCategory} taxonomy that
 * WoGu is organized around, and the immutable model types validators and build-tool
 * integrations exchange: {@link io.wogu.api.ValidationContext},
 * {@link io.wogu.api.RuleResult}, {@link io.wogu.api.ValidatorRunOutcome},
 * {@link io.wogu.api.ValidationSummary}, {@link io.wogu.api.Violation},
 * {@link io.wogu.api.CallPathFrame}, and {@link io.wogu.api.Severity}.
 *
 * <p>This module has no dependency on any workflow engine (Temporal, Conductor, ...), any
 * parser, or any build tool. Engine-specific validators (e.g. {@code wogu-temporal})
 * depend only on this module, which keeps them pluggable without ever requiring a change
 * to the core engine.
 */
package io.wogu.api;
