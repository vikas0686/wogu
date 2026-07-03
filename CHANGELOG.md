# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/) (see
[VERSIONING.md](VERSIONING.md) for how that applies across modules).

## [Unreleased]

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
