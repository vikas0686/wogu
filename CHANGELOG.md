# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/) (see
[VERSIONING.md](VERSIONING.md) for how that applies across modules).

## [Unreleased]

### Changed

- **BREAKING: Maven groupId moved from `io.github.vikas0686` to `dev.wogu`** (domain-verified
  against `wogu.dev` on Sonatype Central), and the Gradle plugin id moved from
  `io.github.vikas0686.wogu` to `dev.wogu` (domain-verified on the Gradle Plugin Portal).
  Artifact ids are unchanged (`wogu-api`, `wogu-core`, `wogu-temporal`, `wogu-report`,
  `wogu-maven-plugin`). Versions through 0.1.2 remain published under the old coordinates
  on Maven Central (artifacts there are immutable); consumers must update their
  `groupId`/plugin `id` to pick up 1.0.0 and later.

### Added

- **WG012 — MutableSideEffect with reference-equality value type** (Determinism). Flags
  `Workflow.mutableSideEffect(id, valueClass, updateFunction, func)` calls whose value type
  relies on inherited, identity-based `Object.equals()` — since `func` constructs a new
  instance every call, `equals()` never reports "unchanged," so every call appends a new
  history event regardless of whether the logical value actually changed. This is WoGu's
  first genuinely new declarative rule *type*: `mutable-side-effect-equality`, alongside
  the existing `forbidden-method`. Unlike every prior rule, the violation isn't "this call
  happened" but "this call happened with an argument whose *resolved type* lacks value
  equality," so `RuleDefinition` gains a `valueTypeArgumentIndex` field, and a new
  `ValueBasedEqualityArgumentTarget` (`wogu-temporal.callgraph`) inspects the named
  argument's `Class<T>` literal via the symbol solver — treating a Java record, a type with
  its own or an inherited non-`Object` `equals()`, or an unresolvable type (a dependency
  outside WoGu's own classpath) all as safe, the same false-negative-preferring default
  every other rule uses for unresolvable code. `docs/rules/WG012.md` documents it.
- Fixed `SourceRootParser` silently failing to parse `record` declarations and
  pattern-matching `instanceof` — both valid since Java 16, and routine in code written
  against this project's own Java 17 baseline — because its `ParserConfiguration` never set
  a `LanguageLevel` and fell back to JavaParser's more conservative default. Every rule's
  analysis was silently incomplete against any file using either construct; both
  `ParserConfiguration` instances (the main one, and the one `JavaParserTypeSolver` uses
  internally for cross-file resolution) now request `JAVA_17` explicitly.
- **WG011 — I/O or blocking calls inside Workflow.sideEffect()** (Determinism). Flags
  network, file, JDBC, and reflection calls reachable from inside a
  `Workflow.sideEffect(...)`/`Workflow.mutableSideEffect(...)` callback — a real
  availability hazard, since the callback runs synchronously on the workflow thread with
  no Activity-style retry or heartbeat. `RuleDefinition` gains an optional
  `requiredContexts` field (the inverse of the existing `suppressedContexts`): where
  `suppressedContexts` excludes matches found in a given `ExecutionContext`,
  `requiredContexts` keeps only matches found in one, letting a rule apply *exclusively*
  inside a context like `SIDE_EFFECT`/`MUTABLE_SIDE_EFFECT` instead of everywhere but it.
  No call-graph engine changes were needed: `CallGraphAnalyzer`'s existing traversal
  already finds calls nested inside a callback's lambda body and already tags each match's
  `ExecutionContext` via `effectiveContext`, however many hops deep — WG011 is a plain
  `forbidden-method` YAML rule reusing both exactly as WG001 already does for the opposite
  filter. `docs/rules/WG011.md` documents it.
- **WG004 — Math.random() inside Workflow**, **WG005 — java.util.Random inside
  Workflow**, **WG006 — ThreadLocalRandom inside Workflow**, **WG007 — SecureRandom
  inside Workflow**, **WG008 — System.getenv() inside Workflow**, **WG009 —
  System.getProperty() inside Workflow**, and **WG010 — ExecutorService inside
  Workflow** (all Determinism). Each is a YAML definition executed by the existing
  `ForbiddenMethodRule`, reusing the same call-graph engine and `TemporalRuleSupport`
  plumbing as WG001–WG003 — no new Java class per rule, no new traversal logic, no report
  changes. `docs/rules/WG004.md` through `WG010.md` document each one.
- `dev.wogu.temporal.callgraph.ConstructorCallTarget`: a `CallTarget` for "this is a `new
  SomeClass(...)` call" (WG005's `new Random()`, WG007's `new SecureRandom()`, WG010's
  `new Thread()`), sharing its class-name-matching rules with `StaticMethodCallTarget` via
  a new package-private `QualifiedClassNameMatcher` helper so the two never disagree about
  what counts as "this class." `RuleDefinition` gains an optional `constructors` field
  (a list of fully qualified class names) alongside `methods`, and `ForbiddenMethodRule`
  builds a `ConstructorCallTarget` per entry, combined with its `methods` targets.
- `StaticMethodCallTarget` gains a resolution-based fallback for instance calls where the
  declaring class isn't written at the call site at all (e.g. `randomInstance.nextInt()`,
  needed for WG005/WG007): when none of the existing syntactic forms match, it resolves
  the call via WoGu's symbol solver and checks that its declaring type is the target
  class. Purely additive — every existing static-call match keeps matching syntactically
  exactly as before, so WG001–WG003 are unaffected.
- **Activity-aware traversal.** `CallGraphAnalyzer.findCallPaths` gains an overload taking
  a `Predicate<MethodDeclaration> traversalBoundary`: a method the predicate matches is
  treated as opaque — neither scanned for matches nor recursed into — while
  `CallGraphAnalyzer` itself stays engine-agnostic (it has no idea what an Activity is,
  only that some methods are marked out of bounds). New `dev.wogu.temporal.ActivityAwareness`
  supplies that predicate for Temporal: a method counts as Activity-owned if it (or its
  declaring class) carries `@ActivityMethod`/`@ActivityInterface`, or its declaring class
  implements an `@ActivityInterface`-annotated interface. `TemporalWorkflowValidator`
  computes this once per `validate()` call and passes it to every rule. This is
  defense-in-depth on top of the traversal's existing dead end at an Activity's interface
  method (which has no body to look inside regardless): it specifically covers a
  reference typed as the Activity *implementation* class directly, where resolution would
  otherwise reach real, callable source and (before this change) report violations that
  only occur inside Activity code, which isn't subject to workflow replay determinism.
- `CallGraphAnalyzer` now also scans every `ObjectCreationExpr` reachable from an entry
  point, checking each against `CallTarget.matchesConstructor`/`describeConstructor` (new
  default methods on `CallTarget`, defaulting to "never matches" so every existing
  method-call-only target needs no change).

### Changed

- **Forbidden-method rules are now declarative YAML, not Java classes.** WG001, WG002,
  and WG003 are each a small YAML file under `wogu-temporal/src/main/resources/rules`
  (`id`, `type: forbidden-method`, metadata, `description`, `replacement`, `methods`),
  executed by one new generic `ForbiddenMethodRule`. The three hand-written
  `UuidRandomUuidRule` / `ThreadSleepRule` / `NonDeterministicTimeApiRule` classes are
  deleted. New `RuleDefinitionLoader` scans the classpath for `rules/*.yaml` (as an
  exploded directory or packaged inside a jar) and parses each into a validated,
  strongly-typed `RuleDefinition` — the only class in WoGu aware the format is YAML. New
  `RuleRegistry` maps a definition's `type` to the `TemporalRule` that executes it (a
  `Map`-based factory registry, today just `forbidden-method` → `ForbiddenMethodRule`).
  New `RuleDefinition.toRule()` maps the common metadata fields onto `dev.wogu.api.Rule`,
  shared by every declarative rule type. New `CustomRule` base class is the designed
  extension point for future rules that need real analysis logic instead of a
  method-call pattern (unused today). Adding another `forbidden-method` rule is now
  "add a YAML file" — no Java, no registration step, no report or engine change.
  Zero visible behavior change: every rule's id, category, severity, message, and
  suggested-fix text is byte-for-byte identical to before.

### Added

- **WG002 — Thread.sleep() inside Workflow** (Determinism). Flags `Thread.sleep(...)`
  reachable from a Temporal workflow entry point, via the same call-graph engine as
  WG001. Suggested fix: `Workflow.sleep(Duration)`.
- **WG003 — Non-deterministic Time APIs inside Workflow** (Determinism). Flags
  `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()`,
  `OffsetDateTime.now()`, `ZonedDateTime.now()`, `Clock.systemUTC()`, and
  `Clock.systemDefaultZone()` reachable from a Temporal workflow entry point. Suggested
  fix: `Workflow.currentTimeMillis()`.
- `docs/rules/WG002.md` and `docs/rules/WG003.md`, following the WG001.md template.
- `dev.wogu.temporal.callgraph.StaticMethodCallTarget`: a reusable `CallTarget` for "this
  call is a specific class's specific static method" (qualified class name + method
  name), handling explicit imports, wildcard imports, `java.lang`'s no-import-needed
  classes (with correct handling of a shadowing import), fully qualified inline calls,
  and static imports. Used by all three rules; adding WG002/WG003 required no new
  AST-matching logic, only new instances of this class.
- `dev.wogu.temporal.TemporalRuleSupport`: the shared "for each workflow class, for each
  entry point, for each target, convert call-graph matches into `Violation`s" logic every
  call-graph-based rule needs, extracted so each rule's `evaluate()` is a one-liner
  instead of duplicating the loop and file-path relativization.

### Changed

- `UUIDRandomValidator`'s WG001-specific `UuidRandomUuidCallTarget` is replaced by the
  generic `StaticMethodCallTarget("java.util.UUID", "randomUUID")`; no behavior change.
- `sample-temporal-project`'s `PaymentService` now violates all three rules
  (`UUID.randomUUID()`, `Thread.sleep()`, `System.currentTimeMillis()`, each in its own
  method called from the workflow), so `mvn verify` now demonstrates all three rules
  failing together with their individual call paths, instead of only WG001.

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
- **Call graph analysis.** New `dev.wogu.temporal.callgraph.CallGraphAnalyzer` performs a
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
- **Console output.** New shared `dev.wogu.core.ConsoleReportRenderer` (used by both
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

[Unreleased]: https://github.com/vikas0686/wogu/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/vikas0686/wogu/releases/tag/v0.1.0
