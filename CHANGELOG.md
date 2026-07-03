# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/) (see
[VERSIONING.md](VERSIONING.md) for how that applies across modules).

## [Unreleased]

### Changed

- **Rules replace validators as WoGu's primary concept.** `WorkflowValidator` now declares
  `rules(): List<Rule>` and its `validate()` returns a `ValidatorRunOutcome` (one
  `RuleResult` per rule, plus a scanned-element count for diagnostics), instead of a
  single validator-keyed `ValidationResult`. `ValidationResult` is renamed `RuleResult`.
  `Violation` now references a `Rule` (id, title, category, severity, engine,
  since-version, documentation reference, auto-fix availability) instead of a bare
  `validatorId` + `severity`. New `RuleCategory` enum (Determinism, Activities,
  Versioning, Signals, Updates, Performance, Best Practices, Security, Organization
  Policies), each reserving a fixed `WG###` numeric range that `Rule`'s constructor
  enforces. `wogu-temporal`'s `UUIDRandomValidator` is replaced by
  `TemporalWorkflowValidator` (the registered validator) plus `UuidRandomUuidRule`
  (rule `WG001`).
- **Call graph analysis.** New `io.wogu.temporal.callgraph.CallGraphAnalyzer` performs a
  depth-first traversal from a workflow entry-point method, following every call it can
  resolve to source elsewhere in the project, however many hops deep, and reports every
  call site matching a `CallTarget` with the full path from the entry point down to the
  match (`CallGraphMatch`/`Violation.callPath()`, a list of `CallPathFrame`s, new in
  `wogu-api`). WG001 now uses this engine instead of scanning only the workflow
  implementation class directly, so a violation several method calls away from the
  workflow entry point is now caught. Unresolvable calls (third-party libraries,
  reflection, dynamic dispatch) are a traversal boundary, not an error. Designed to be
  reused by future rules (`Thread.sleep()`, `Instant.now()`, `Math.random()`, HTTP/JDBC/
  file I/O), not just WG001.
- **HTML report redesign.** "Validator Summary" is now "Rule Summary" (Rule ID/Title/
  Category/Severity/Status/Violations/Execution Time; the rule id links to its
  documentation only when that reference is a real URL). "Overview" is now "Build
  Information" and additionally shows WoGu version, Java version, and build tool.
  Violations are rendered as detail cards (not table rows) showing the full call path,
  a teaching-style explanation of the rule, and the recommended fix.
- **Console output.** New shared `io.wogu.core.ConsoleReportRenderer` (used by both
  plugins) prints a banner, a scan summary ("Found N workflow classes"), a checkmark/cross
  line per rule (with the rule's title shown on failure), violation counts grouped by
  severity, and the build status, replacing the previous plainer, validator-oriented log
  lines.
- `ValidationContext` gains `buildTool()` ("Maven"/"Gradle"), set by each plugin and
  surfaced in the report's Build Information section.

### Added

- `docs/rules/WG001.md`: full rule documentation (Problem, Why this matters, Bad/Good
  Example, Recommended Fix, References, False Positives, Since Version) — the template
  every future rule's doc follows.
- `sample-temporal-project` now demonstrates the call graph engine: its workflow method
  calls into a `PaymentService` class, where the actual `UUID.randomUUID()` call lives,
  one hop away from the workflow entry point.

## [0.1.0] - 2026-07-03

Initial proof-of-concept release.

### Added

- `wogu-api`: the `WorkflowValidator` SPI and immutable model types
  (`ValidationContext`, `ValidationResult`, `ValidationSummary`, `Violation`, `Severity`).
- `wogu-core`: `ValidationEngine`, discovering validators via `ServiceLoader` and
  aggregating their results.
- `wogu-temporal`: `UUIDRandomValidator`, the first validation rule — flags
  `UUID.randomUUID()` calls inside Temporal workflow implementation classes, which break
  replay determinism. Detection is syntactic (JavaParser), with no compile-time
  dependency on the Temporal SDK.
- `wogu-report`: `HtmlReportGenerator`, rendering a `ValidationSummary` as a
  self-contained `index.html` (plain HTML/CSS, no JavaScript).
- `wogu-maven-plugin`: the `wogu:validate` goal, bound to the `verify` phase by default.
- `wogu-gradle-plugin`: the `woguValidate` task, wired to the `build` lifecycle task once
  the `java` plugin is applied.
- `sample-temporal-project`: a Temporal workflow implementation calling
  `UUID.randomUUID()`, intentionally failing `mvn verify` and demonstrating the
  generated HTML report end-to-end.

[Unreleased]: https://github.com/wogu-project/wogu/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/wogu-project/wogu/releases/tag/v0.1.0
