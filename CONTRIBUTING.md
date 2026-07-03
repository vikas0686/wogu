# Contributing to WoGu

Thanks for your interest in contributing! This document covers how the repository is
built, how to add a new rule, and what we expect from a pull request.

## Prerequisites

* JDK 17 or newer
* Maven 3.9+ (a wrapper is not currently bundled at the repo root; a system install is
  assumed)
* Gradle is only needed if you want to install it yourself; `wogu-gradle-plugin` bundles
  its own wrapper (`./gradlew`), so no global Gradle install is required to build it.

## Repository layout

```
wogu-parent          (root aggregator pom.xml)
  wogu-api           SPI: WorkflowValidator, Rule, RuleCategory, RuleResult,
                     ValidatorRunOutcome, ValidationContext, ValidationSummary,
                     Violation, CallPathFrame, Severity. No engine dependencies.
  wogu-core          ValidationEngine (ServiceLoader-based discovery and execution) and
                     ConsoleReportRenderer, shared by both build-tool plugins.
  wogu-temporal       Temporal SDK rules (WG001-WG003 today, all declarative YAML under
                     src/main/resources/rules), the RuleRegistry/RuleDefinitionLoader
                     pipeline and generic ForbiddenMethodRule that execute them, the
                     CallGraphAnalyzer engine they're built on, and the
                     WorkflowImplementationScanner they share.
  wogu-report        HtmlReportGenerator: renders a ValidationSummary as index.html.
  wogu-maven-plugin  The wogu:validate Maven goal.
  wogu-gradle-plugin Independent Gradle build; the woguValidate Gradle task.
  sample-temporal-project
                     A Temporal workflow whose service class violates all three rules
                     (UUID.randomUUID(), Thread.sleep(), System.currentTimeMillis());
                     mvn verify intentionally fails here and writes target/wogu/index.html,
                     showing all three violations with their call paths.
docs/rules/          One Markdown file per rule (see WG001.md for the template).
```

`wogu-gradle-plugin` is **not** a Maven module — it's a separate Gradle build that
consumes the Maven-built jars from `~/.m2` via `mavenLocal()`. `sample-temporal-project`
*is* a Maven module, but is excluded from the repo's default Maven reactor via the
`with-samples` profile, since it fails by design.

## Building

Build and test the core Maven reactor (`wogu-api` through `wogu-maven-plugin`):

```bash
mvn clean verify
```

This does **not** build `sample-temporal-project`, since it fails by design. To run it
explicitly:

```bash
# Fails on purpose, writes sample-temporal-project/target/wogu/index.html:
mvn -f sample-temporal-project verify

# Or via the opt-in profile from the repo root (the reactor will report FAILURE,
# because the sample is supposed to fail):
mvn -Pwith-samples verify
```

Because `wogu-gradle-plugin` resolves WoGu's jars from your local Maven repository, run
`mvn install` (not just `verify`) at the repo root first if you've changed any Maven
module and want the Gradle plugin to see the change:

```bash
mvn clean install
cd wogu-gradle-plugin && ./gradlew build
```

## Rules vs. validators

WoGu revolves around **rules** (`WG001`, `WG002`, ...), not validator implementations. A
single `WorkflowValidator` (the SPI type in `wogu-api`) commonly evaluates several rules
in one pass over a project — sharing one parse of the source, one workflow scan, one call
graph — because most of that work is identical across rules for the same engine.
`Rule` (metadata: id, title, category, severity, engine, since-version, documentation,
auto-fix) and `RuleResult` (a rule's outcome) are what reports and future per-rule
configuration key off of, never the validator implementation that happened to produce
them.

## Rule numbering

Every rule id is `WG` followed by three digits, and every `RuleCategory` reserves a fixed
numeric range — enforced by `Rule`'s constructor, not just documented, so constructing a
rule with a mismatched id/category throws immediately:

| Range | Category |
|---|---|
| WG001–WG099 | Determinism |
| WG100–WG199 | Activities |
| WG200–WG299 | Versioning |
| WG300–WG349 | Signals |
| WG350–WG399 | Updates |
| WG400–WG499 | Performance |
| WG500–WG599 | Best Practices |
| WG600–WG699 | Security |
| WG900–WG999 | Organization Policies |

## Adding a new rule

Most rules need **no Java code at all**. This is the primary extension point the
architecture is built around, and it should never require touching `wogu-core` or
`wogu-report`.

### Declarative rules (the common case)

If your rule is "flag every reachable call to this specific static method" — which is
what WG001, WG002, and WG003 all are — it's a YAML file, nothing more:

1. Add `wogu-temporal/src/main/resources/rules/wg0nn.yaml` with `type: forbidden-method`,
   the metadata fields (`id`, `title`, `category`, `severity`, `engine`, `since`,
   `documentation`), `description` (the teaching-style explanation, becomes the
   violation's message), `replacement` (the suggested fix), and a `methods` list of fully
   qualified `Class.method` references (e.g. `java.lang.Thread.sleep`). See any of
   `wg001.yaml`/`wg002.yaml`/`wg003.yaml` for the exact shape.
2. Give it the next free id in the right category's range (see above) — `Rule`'s
   constructor rejects a mismatch, so getting this wrong fails loudly, not silently.
3. Add `docs/rules/WG0NN.md` following the template in `WG001.md`: Problem, Why this
   matters, Bad Example, Good Example, Recommended Fix, References, False Positives, Since
   Version.
4. Add a test. `wogu-temporal`'s existing rule tests (e.g. the WG002/WG003 test classes)
   write real source to a `@TempDir` and validate through `TemporalWorkflowValidator` end
   to end — don't mock JavaParser types, and don't write a new AST scanner: reuse
   `WorkflowImplementationScanner` (workflow classes and entry points) and
   `CallGraphAnalyzer` (reachability), which `ForbiddenMethodRule` already wires up for you.

`RuleDefinitionLoader` scans the classpath for `rules/*.yaml` at startup — there is no
filename to register anywhere, in Java or otherwise. New rule *types* beyond
`forbidden-method` (e.g. a future `forbidden-constructor` or `required-annotation`) are
added by registering one more factory in `RuleRegistry`'s type map; that's the only place
a rule "type" is dispatched, and it's a data-driven map, not a switch statement or an
if/else chain.

### Custom (hand-written) rules — the exception

Some rules genuinely can't be expressed as a method-call pattern — a workflow-complexity
check, a ContinueAsNew recommendation, versioning safety, activity configuration
validation. For those, extend `CustomRule` (implements `TemporalRule`, handles metadata
storage for you) and add an instance to `TemporalWorkflowValidator`'s `CUSTOM_RULES` list
(empty today). Everything else about how the rule is registered, executed, and reported
is identical to a declarative rule — `CustomRule` only replaces *how violations are found*,
not the surrounding machinery.

Either way, `ValidationEngine.discover()` still only ever discovers the one registered
`WorkflowValidator` per engine module via `ServiceLoader` — the registry described above
lives entirely inside `wogu-temporal`, one level below that.

## Adding a new workflow engine (Conductor, Camunda, Airflow, ...)

Create a new module (e.g. `wogu-conductor`) that depends only on `wogu-api`, implement
`WorkflowValidator` there, and register it the same way via `META-INF/services`. Neither
`wogu-core` nor `wogu-report` nor the build-tool plugins need to change: `wogu-core`
discovers validators reflectively, and `wogu-report` renders whatever `ValidationSummary`
it's given — and whatever `Rule` metadata each `RuleResult` carries — regardless of which
engine produced it.

## Code style

* Java 17, package-private where possible, public only what belongs to the module's
  contract.
* Immutable model types (builders or records).
* JavaDoc on public types and methods explaining the *why*, not the *what*.
* No `TODO`s and no placeholder implementations — if something isn't ready, don't merge it.

## Pull requests

* Keep changes scoped to one module/concern where practical; the commit history should
  read as a sequence of complete, buildable steps.
* Include tests for new behavior. `wogu-temporal` and `wogu-report` favor small,
  table-driven unit tests; `wogu-maven-plugin` and `wogu-gradle-plugin` favor end-to-end
  tests (a real `MavenProject`/`WoguRunner` call, or Gradle TestKit) over mocking the
  build tool APIs.
* Run `mvn clean verify` (and the Gradle builds, if you touched them) before opening a PR.
