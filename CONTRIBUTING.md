# Contributing to WoGu

Thanks for your interest in contributing! This document covers how the repository is
built, how to add a new validator, and what we expect from a pull request.

## Prerequisites

* JDK 17 or newer
* Maven 3.9+ (a wrapper is not currently bundled at the repo root; a system install is
  assumed)
* Gradle is only needed if you want to install it yourself; `wogu-gradle-plugin` and the
  Gradle side of the sample project each bundle their own wrapper (`./gradlew`), so no
  global Gradle install is required to build them.

## Repository layout

```
wogu-parent          (root aggregator pom.xml)
  wogu-api           SPI: WorkflowValidator, ValidationContext, ValidationResult,
                     ValidationSummary, Violation, Severity. No engine dependencies.
  wogu-core          ValidationEngine: ServiceLoader-based discovery and execution.
  wogu-temporal       Temporal SDK validators (UUIDRandomValidator, and the
                     WorkflowImplementationScanner they share).
  wogu-report        HtmlReportGenerator: renders a ValidationSummary as index.html.
  wogu-maven-plugin  The wogu:validate Maven goal.
  wogu-gradle-plugin Independent Gradle build; the woguValidate Gradle task.
  sample-temporal-project
    clean/           Uses Workflow.randomUUID(); mvn verify / gradle build both pass.
    violation/       Uses UUID.randomUUID(); both intentionally fail, with a report.
```

`wogu-gradle-plugin` and `sample-temporal-project/*` are **not** Maven modules — they are
separate Gradle builds that consume the Maven-built jars from `~/.m2` (via `mavenLocal()`
in `wogu-gradle-plugin/build.gradle.kts`, and via a composite `includeBuild(...)` from the
sample projects into `wogu-gradle-plugin`). `sample-temporal-project` itself *is* a Maven
aggregator (for its `clean` and `violation` children) but is excluded from the repo's
default Maven reactor via the `with-samples` profile, since `violation` fails on purpose.

## Building

Build and test the core Maven reactor (`wogu-api` through `wogu-maven-plugin`):

```bash
mvn clean verify
```

This does **not** build `sample-temporal-project`, since its `violation` module fails by
design. To exercise the samples:

```bash
# Passes:
mvn -f sample-temporal-project/clean verify

# Fails on purpose, writes sample-temporal-project/violation/target/wogu/index.html:
mvn -f sample-temporal-project/violation verify

# Or build both together via the opt-in profile (the reactor will report FAILURE,
# because 'violation' is supposed to fail):
mvn -Pwith-samples verify
```

Because `wogu-gradle-plugin` and the samples' Gradle builds resolve WoGu's jars from your
local Maven repository, run `mvn install` (not just `verify`) at the repo root first if
you've changed any Maven module and want the Gradle side to see the change:

```bash
mvn clean install
cd wogu-gradle-plugin && ./gradlew build
cd ../sample-temporal-project/clean && ./gradlew build       # passes
cd ../violation && ./gradlew build                            # fails on purpose
```

## Adding a new Temporal validator

This is the extension point the whole architecture is built around — it should never
require touching `wogu-core`:

1. Implement `io.wogu.api.WorkflowValidator` in `wogu-temporal` (or a new module, if the
   validator belongs to a different workflow engine — see below).
2. Add its fully qualified class name as a new line in
   `wogu-temporal/src/main/resources/META-INF/services/io.wogu.api.WorkflowValidator`.
3. Write tests. If your validator needs to know whether a class is a workflow
   implementation, reuse `WorkflowImplementationScanner` rather than re-deriving that
   logic.

That's it — `ValidationEngine.discover()` picks up any validator on the classpath with a
service declaration. No registry, no switch statement, no core change.

## Adding a new workflow engine (Conductor, Camunda, Airflow, ...)

Create a new module (e.g. `wogu-conductor`) that depends only on `wogu-api`, implement
`WorkflowValidator` there, and register it the same way via `META-INF/services`. Neither
`wogu-core` nor `wogu-report` nor the build-tool plugins need to change: `wogu-core`
discovers validators reflectively, and `wogu-report` renders whatever `ValidationSummary`
it's given, regardless of which engine produced it.

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
