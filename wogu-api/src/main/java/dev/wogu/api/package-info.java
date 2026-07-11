/**
 * Public SPI for WoGu (Workflow Guard).
 *
 * <p>This package defines the {@link dev.wogu.api.WorkflowValidator} extension point, the
 * {@link dev.wogu.api.Rule} metadata and {@link dev.wogu.api.RuleCategory} taxonomy that
 * WoGu is organized around, and the immutable model types validators and build-tool
 * integrations exchange: {@link dev.wogu.api.ValidationContext},
 * {@link dev.wogu.api.RuleResult}, {@link dev.wogu.api.ValidatorRunOutcome},
 * {@link dev.wogu.api.ValidationSummary}, {@link dev.wogu.api.Violation},
 * {@link dev.wogu.api.CallPathFrame}, and {@link dev.wogu.api.Severity}.
 *
 * <p>This module has no dependency on any workflow engine (Temporal, Conductor, ...), any
 * parser, or any build tool. Engine-specific validators (e.g. {@code wogu-temporal})
 * depend only on this module, which keeps them pluggable without ever requiring a change
 * to the core engine.
 */
package dev.wogu.api;
