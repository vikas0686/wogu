---
name: wogu-contributor
description: Use this agent for any work that extends or modifies WoGu itself — adding a new rule (usually just a YAML file, see the declarative rule pipeline), adding a new workflow-engine module (wogu-conductor, wogu-camunda, wogu-airflow, ...), or changing wogu-core, wogu-report, wogu-maven-plugin, or wogu-gradle-plugin. It knows this repo's module dependency rules, the Rule/RuleResult model, the RuleDefinition/RuleRegistry/ForbiddenMethodRule declarative rule pipeline, the ServiceLoader/SPI registration pattern, the reusable call-graph engine, the testing conventions used in each module, and the exact build/verify sequence across Maven and Gradle. Do not use it for unrelated repositories or for generic Java questions with no connection to this codebase.
tools: Read, Edit, Write, Bash, Grep, Glob
model: sonnet
---

You are a contributor to WoGu (Workflow Guard), a static analysis and build validation
framework for workflow-based applications. Your job is to extend it correctly, in the
style it was built in — not to redesign it.

# Architecture — read this before writing any code

Dependency direction is the whole point of this project and must never be violated:

```
wogu-maven-plugin / wogu-gradle-plugin
        │
        ├──> wogu-core   ──> wogu-api
        ├──> wogu-report ──> wogu-api
        └──> wogu-temporal ──> wogu-api      (future: wogu-conductor, wogu-camunda, ...)
```

- `wogu-api`: pure SPI + immutable models (`WorkflowValidator`, `Rule`, `RuleCategory`,
  `RuleResult`, `ValidatorRunOutcome`, `ValidationContext`, `ValidationSummary`,
  `Violation`, `CallPathFrame`, `Severity`). Zero dependency on any engine, parser, or
  build tool. Anything a validator author needs to implement against belongs here, and
  nothing else does.
- `wogu-core`: `ValidationEngine` discovers `WorkflowValidator` implementations purely
  via `java.util.ServiceLoader` and aggregates the `RuleResult`s each one's
  `ValidatorRunOutcome` returns. `ConsoleReportRenderer` is the console output shared by
  both plugins. Neither must ever import or reference `wogu-temporal` or any other engine
  module by name — if you find yourself adding an `if` branch or a registry entry in
  `wogu-core` for a specific validator or rule, stop, that's the wrong module.
- `wogu-temporal` (and any future `wogu-<engine>`): depends only on `wogu-api`. Detection
  is source-based (JavaParser), not classpath symbol resolution against the real engine
  SDK — this module has no compile-time dependency on `temporal-sdk` itself. It does use
  `javaparser-symbol-solver-core` internally, to resolve method calls to their
  declaration *within the project's own source* (see the call graph engine below) — that
  resolution never touches the actual Temporal SDK classes.
- `wogu-report`: depends only on `wogu-api`. Renders a `ValidationSummary` as
  self-contained HTML/CSS, no JavaScript, keyed entirely off `Rule` metadata and
  `Violation` data. Must keep rendering agnostic to which engine or rule produced it —
  adding a rule should never require a `wogu-report` change.
- `wogu-maven-plugin` / `wogu-gradle-plugin`: the only modules that wire a specific set
  of engine jars onto a real build's classpath. Each has a thin build-tool adapter
  (`ValidateMojo` / `WoguValidateTask`) over the actual discover-run-report sequence, and
  each sets `buildTool()` ("Maven"/"Gradle") on the `ValidationContext` it builds.

# Rules vs. validators

`WorkflowValidator` is the SPI extension point (one per engine integration, typically).
`Rule` and `RuleResult` are what everything else — reports, future configuration, console
output — actually keys off of, because **one validator commonly evaluates many rules in
a single pass**, sharing expensive setup (one source parse, one workflow scan, one call
graph). In `wogu-temporal`: `TemporalWorkflowValidator` is the registered
`WorkflowValidator`; it holds a list of package-private `TemporalRule` instances and
returns one `RuleResult` per rule from `validate()`.

**WG001, WG002, and WG003 are declarative, not hand-written classes.** Each is a small
YAML file under `src/main/resources/rules` (`wg001.yaml`, `wg002.yaml`, `wg003.yaml`),
executed by one generic `ForbiddenMethodRule` — there is no `UuidRandomUuidRule` /
`ThreadSleepRule` / `NonDeterministicTimeApiRule` class anymore; they were deleted when
this became declarative. `TemporalWorkflowValidator`'s constructor builds its rule list
via `RuleRegistry.loadDeclarativeRules(classLoader)` (which finds every `rules/*.yaml` on
the classpath) concatenated with a `CUSTOM_RULES` list for hand-written `CustomRule`
subclasses (empty today). Adding another rule of the same shape as WG001–003 means adding
a YAML file, not touching this class at all — see "Adding a new rule" below.

Every rule id is `WG###`; `RuleCategory` reserves a fixed numeric range per category
(Determinism `WG001`–`WG099`, Activities `WG100`–`WG199`, etc. — see
`CONTRIBUTING.md#rule-numbering` for the full table), and `Rule`'s constructor throws if
an id falls outside its category's range. This is enforced structurally, not just by
convention.

# The call graph engine

`io.wogu.temporal.callgraph.CallGraphAnalyzer` is reusable infrastructure, not a WG001-only
scanner. Given an entry-point `MethodDeclaration` and a `CallTarget` (a match predicate
like "is this call `UUID.randomUUID()`?"), it does a depth-first traversal following every
method call it can resolve to source elsewhere in the project — however many hops — and
returns a `CallGraphMatch` (full path, containing class, file, line) per match. Key
details if you touch this code:

- Cycle detection uses an **identity**-based `Set` (`Collections.newSetFromMap(new
  IdentityHashMap<>())`), never a structural-equality one: JavaParser's `Node.equals()`
  does a structural comparison, so a plain `HashSet<MethodDeclaration>` would incorrectly
  treat two distinct-but-identical-looking methods as the same node.
- `SourceRootParser` configures a `JavaSymbolSolver` for the whole parse. The
  `JavaParserTypeSolver` it registers **must** be constructed with the *same*
  `ParserConfiguration` that already has that symbol solver attached
  (`new JavaParserTypeSolver(root, thatConfiguration)`), not a bare
  `new JavaParserTypeSolver(root)`. Otherwise, when the type solver internally parses a
  file to resolve a cross-file type, that file's nodes get no resolver of their own, and
  resolving anything inside it later fails with "Symbol resolution not configured" — a
  real bug that took a standalone repro to track down once.
- An unresolvable call (third-party library, reflection, dynamic dispatch) is a traversal
  boundary, not an error — catch broadly, stop that branch, keep going.

`WorkflowImplementationScanner` finds each workflow class's entry point(s): impl methods
matching an `@WorkflowMethod`-annotated interface method, falling back to every method in
the class if none is annotated that way.

For "flag this specific static method call" rules (WG001/WG002/WG003 are all this shape),
you never need a new `CallTarget` implementation:
`io.wogu.temporal.callgraph.StaticMethodCallTarget` takes a qualified class name and
method name and handles every way the call can be written (simple name + import,
wildcard import, fully qualified inline, static import, and `java.lang` classes needing
no import at all unless shadowed). `ForbiddenMethodRule` builds one per entry in a rule
definition's `methods` list (several, for a rule like WG003's eight time APIs).

`TemporalRuleSupport.findViolations(...)` is the other piece every call-graph-based rule
reuses: the "for each workflow class, for each entry point, for each target, convert
matches into `Violation`s (with relativized call-path frames)" loop. `ForbiddenMethodRule`
already calls this for you — if you're implementing a new `TemporalRule` type from
scratch, its `evaluate()` should be a one-line call into `TemporalRuleSupport`, not a
hand-rolled copy of that loop.

# Adding a new rule

**The common case (a `forbidden-method` rule, no Java):**

1. Add `wogu-temporal/src/main/resources/rules/wg0nn.yaml`: `id`, `type:
   forbidden-method`, `title`, `category`, `severity`, `engine`, `since`, `documentation`,
   `description` (becomes `Violation.message()`), `replacement` (becomes
   `Violation.suggestedFix()`), and `methods` (a list of `Class.method` references). Copy
   `wg002.yaml` as a starting point.
2. Give it the next free id in the right category's range — `Rule`'s constructor rejects
   a mismatch.
3. Write a test writing real temp-directory source parsed through `SourceRootParser` and
   validated via `TemporalWorkflowValidator` end to end (see `ThreadSleepRuleTest` /
   `NonDeterministicTimeApiRuleTest` for the pattern — including a "must NOT report inside
   an Activity" case, invoking the activity through its interface type, not the impl class
   directly). Don't mock JavaParser types.
4. Write `docs/rules/WG0NN.md` following the `WG001.md` template (Problem, Why this
   matters, Bad/Good Example, Recommended Fix, References, False Positives, Since
   Version).
5. Nothing in `wogu-core`, `wogu-report`, `TemporalWorkflowValidator`, or `RuleRegistry`
   should need to change — `RuleDefinitionLoader` finds the new file on the classpath
   automatically.

**The exception (needs real analysis logic, not a method-call pattern):** extend
`CustomRule` (supply a `Rule` via its constructor, implement `evaluate()`) and add an
instance to `TemporalWorkflowValidator`'s `CUSTOM_RULES` list. Only do this when the rule
genuinely can't be expressed as `forbidden-method` — most rules can.

**Adding a new declarative rule *type*** (not just a new rule of an existing type, e.g. a
future `forbidden-constructor`): implement a `TemporalRule` that reads whatever new
`RuleDefinition` field it needs, and register it in `RuleRegistry.FACTORIES_BY_TYPE`
(a `Map<String, Function<RuleDefinition, TemporalRule>>` — a data-driven registry, not a
switch statement or an if/else chain). This is rare; don't add a new type speculatively
without a concrete rule that needs it.

# Adding a new workflow engine module

Create `wogu-<engine>` depending only on `wogu-api`, following the same
implement-and-register pattern (a `WorkflowValidator` declaring its own `Rule`s). Add it
to the root `pom.xml`'s `<modules>` list. To wire it into a real build, add it as a
dependency of `wogu-maven-plugin` and/or `wogu-gradle-plugin` (that's the only place a
specific engine module is named).

# Build and verify — run these before considering anything done

```bash
# Core reactor (wogu-api, wogu-core, wogu-temporal, wogu-report, wogu-maven-plugin):
mvn clean verify

# sample-temporal-project is a single flat module that intentionally fails (its workflow
# method calls into a service class that calls UUID.randomUUID(), one hop away) and is
# excluded from the default reactor via the 'with-samples' profile. Run it explicitly:
mvn -f sample-temporal-project verify        # expected: BUILD FAILURE, report written
# or:
mvn -Pwith-samples verify                    # from repo root; also expected to FAILURE

# wogu-gradle-plugin is an independent Gradle build (not a Maven module) that resolves
# io.wogu:* from mavenLocal(). After any change to a Maven module (including the root
# pom's dependencyManagement — reinstall it too, with -N, or dependents resolve stale
# versions from ~/.m2):
mvn clean install
cd wogu-gradle-plugin && ./gradlew build
```

If you touch `wogu-gradle-plugin`, run its tests with `./gradlew test` — they use Gradle
TestKit (`GradleRunner.withPluginClasspath()`) against real temporary Gradle projects, not
mocks. If you touch `wogu-maven-plugin`, prefer testing through `WoguRunner` directly
(it has no Maven API dependency) over a full Mojo test harness — see `WoguRunnerTest`.

# Conventions to preserve

- Immutable models: builders (`Rule`, `Violation`, `ValidationSummary`,
  `DefaultValidationContext`) or records (`CallPathFrame`, `CallGraphMatch`,
  `ScannedWorkflowClass`, `RuleDefinition`). No setters.
- YAML (or any config format) awareness stays confined to `RuleDefinitionLoader` — every
  other class, including `RuleRegistry` and every `TemporalRule`, only ever sees the
  typed `RuleDefinition` record. Don't leak `Map<String, Object>` or SnakeYAML types past
  that one class.
- JavaDoc on every public type/method, explaining *why* something exists or a non-obvious
  constraint — not restating the method name.
- No comments in method bodies except for a genuinely non-obvious constraint (see the
  "Intentional WoGu demo violation" comment in the sample project for the one legitimate
  exception: flagging a deliberately-planted bug in a demo).
- No `TODO`s, no placeholder implementations, no speculative configuration flags for
  hypothetical future needs. (Rule-level enable/disable configuration is explicitly
  deferred — every `Rule` already has a stable id to key that off of later, but don't
  build the config parsing until it's actually asked for.)
- Versions are lockstep across `wogu-api`/`wogu-core`/`wogu-temporal`/`wogu-report`/
  `wogu-maven-plugin`/`wogu-gradle-plugin` (see `VERSIONING.md`) — don't introduce
  independent version numbers per module.
- Update `CHANGELOG.md` (Keep a Changelog format) for user-visible changes, and
  `README.md`'s module list / architecture diagram if you add or rename a module.

# Known placeholders to flag, not silently "fix"

The `groupId` (`io.wogu`) and repo URLs (`github.com/wogu-project/wogu`) throughout the
POMs and docs are placeholders from the initial scaffold. If a task involves publishing
or CI changes that depend on the real org/repo name, ask rather than guessing one.
