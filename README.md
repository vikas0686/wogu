# WoGu — Workflow Guard

**Static analysis and build validation for workflow-based applications.**

WoGu plugs into your build (`mvn verify` or `gradle build`) and fails it when your
workflow code violates a workflow-quality rule — the same way JaCoCo made coverage a
build-time concern instead of a manual check, and SpotBugs made bug-pattern detection
part of the build, WoGu aims to do that for workflow correctness.

WoGu is **not** a Temporal-only tool. Temporal is the first supported workflow engine; the
architecture is built so that entirely different engines (Conductor, Camunda, Airflow,
...) and dozens more rules can be added without ever touching the core engine. See
[Architecture](#architecture) and [Adding a rule](#adding-a-rule) below.

## Why

Temporal (and workflow engines like it) replay workflow code from history to reconstruct
state. Anything non-deterministic in that code — a random number, the wall clock, thread
scheduling — can produce a different result on replay than it did originally, diverging
execution from recorded history. These bugs are easy to write and easy to miss in review;
they belong in the build, caught automatically, every time.

## What's implemented

Three Determinism rules for the Temporal Java SDK, all reachable-from-a-workflow-entry-
point checks built on the same [call-graph analysis engine](#call-graph-analysis) — not
just direct usage inside the workflow implementation class, but calls several methods
away:

| Rule | Flags | Fix |
|---|---|---|
| [WG001](docs/rules/WG001.md) | `UUID.randomUUID()` | `Workflow.randomUUID()` |
| [WG002](docs/rules/WG002.md) | `Thread.sleep(...)` | `Workflow.sleep(Duration)` |
| [WG003](docs/rules/WG003.md) | `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()`, `OffsetDateTime.now()`, `ZonedDateTime.now()`, `Clock.systemUTC()`, `Clock.systemDefaultZone()` | `Workflow.currentTimeMillis()` |

Each is a self-contained addition to `TemporalWorkflowValidator`'s rule list — none of
them required a change to `wogu-core`, `wogu-report`, or either build-tool plugin (see
[Adding a rule](#adding-a-rule)).

## Quick start

### Maven

```xml
<plugin>
  <groupId>io.wogu</groupId>
  <artifactId>wogu-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals>
        <goal>validate</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

That's it — `mvn verify` now runs WoGu automatically, bound to the `verify` phase.

### Gradle

```kotlin
plugins {
  id("io.wogu.wogu-gradle-plugin") version "0.1.0-SNAPSHOT"
}
```

Applying the plugin registers `woguValidate` and, once the `java` plugin is present,
wires it into `build`.

> These artifacts aren't published to Maven Central yet (see
> [sample-temporal-project](sample-temporal-project) and [CONTRIBUTING.md](CONTRIBUTING.md)
> for how the sample in this repo consumes them locally in the meantime).

## What it looks like

Console output on a build with violations of all three rules, each reached through one
intermediate method call:

```
----------------------------------------------------
WoGu Workflow Guard
----------------------------------------------------
Scanning project...

✓ Found 1 workflow class

Running Rules...

✗ WG001 UUID.randomUUID() inside Workflow
✗ WG002 Thread.sleep() inside Workflow
✗ WG003 Non-deterministic Time APIs inside Workflow
----------------------------------------
3 ERROR
Build FAILED

HTML Report
target/wogu/index.html
```

And the HTML report it writes (`target/wogu/index.html` for Maven,
`build/reports/wogu/index.html` for Gradle), showing all three rules in the Rule Summary
and a call path from the workflow's entry point down to each offending call — rendered
with no report-generator changes, since it reads purely from `Rule` metadata:

![WoGu report showing a failed build with all three Determinism rules failing, each with its own violation card showing the call path from the workflow entry point down to the offending call](docs/images/report-failed.png)

You can reproduce this directly from this repo — see
[sample-temporal-project](sample-temporal-project), whose workflow method calls into a
service class that violates all three rules:

```bash
mvn -f sample-temporal-project verify   # fails by design, writes the report above
```

## Call graph analysis

The naive version of WG001 would only catch `UUID.randomUUID()` written directly inside a
workflow implementation class. Real workflow code delegates to helper classes and
services, so WoGu instead performs a call-graph traversal: starting from a workflow's
entry-point method(s), it follows every method call it can resolve to source elsewhere in
the project, however many hops deep, looking for the pattern each rule cares about.

```java
@WorkflowMethod
public void processPayment() {
  orderService.createOrder();   // one hop...
}

class OrderService {
  void createOrder() {
    customerService.generateId();   // ...another hop...
  }
}

class CustomerService {
  void generateId() {
    UUID.randomUUID();   // ...and WG001 still finds it here.
  }
}
```

`io.wogu.temporal.callgraph.CallGraphAnalyzer` is this engine, and it's reusable: it takes
a `CallTarget` (the pattern to look for — "is this call `UUID.randomUUID()`?") as a
parameter, so WG002 (`Thread.sleep()`) and WG003 (eight non-deterministic time APIs)
reuse the exact same traversal instead of new one-off scanners — `ForbiddenMethodRule`
builds one `io.wogu.temporal.callgraph.StaticMethodCallTarget` ("this call is a specific
class's specific static method") per entry in a rule's YAML `methods` list. A future rule
for `Math.random()`, HTTP clients, JDBC, or file I/O follows the same pattern. When a call
can't be resolved to project source (a
third-party library, reflection, dynamic dispatch — notably including a call made through
an Activity's interface, which is how Activities are correctly never flagged), traversal
simply stops there rather than guessing — WoGu prefers missing a violation behind an
unresolvable call over reporting a false positive.

## Architecture

```
wogu-parent                  root aggregator (Maven reactor)
  wogu-api                    SPI: WorkflowValidator, Rule, RuleCategory, RuleResult,
                               ValidatorRunOutcome, ValidationContext, ValidationSummary,
                               Violation, CallPathFrame, Severity. Zero dependency on any
                               engine, parser, or build tool.
  wogu-core                   ValidationEngine: discovers WorkflowValidator
                               implementations via java.util.ServiceLoader, runs them,
                               aggregates their RuleResults. ConsoleReportRenderer: the
                               console output shared by both build-tool plugins. No
                               compile-time reference to any specific validator or rule.
  wogu-temporal                Temporal Java SDK rules (WG001–WG003 today, all declared
                               as YAML under src/main/resources/rules and executed by the
                               generic ForbiddenMethodRule), the reusable CallGraphAnalyzer
                               engine, RuleRegistry/RuleDefinitionLoader, and
                               WorkflowImplementationScanner, shared infrastructure for
                               future Temporal-specific rules.
  wogu-report                  HtmlReportGenerator: renders a ValidationSummary as a
                               single, self-contained index.html — Build Information, a
                               Rule Summary table, and a detail card per violation
                               (including its call path). Depends only on wogu-api, so it
                               renders any engine's output; adding a rule never requires a
                               report change.
  wogu-maven-plugin           The wogu:validate Maven goal (bound to verify by default).
  wogu-gradle-plugin           The woguValidate Gradle task (an independent Gradle build).
  sample-temporal-project      Real Temporal SDK code demonstrating a violation reached
                               through an intermediate service class.
docs/rules/                   One Markdown page per rule (WG001.md is the template).
```

**Dependency direction:** `wogu-temporal` and the future `wogu-conductor` /
`wogu-camunda` / `wogu-airflow` modules depend only on `wogu-api`. `wogu-core` depends
only on `wogu-api` too, and discovers every engine's validators reflectively — it has no
import of, and no build dependency on, `wogu-temporal` or any other engine module.
`wogu-report` also depends only on `wogu-api`. The two build-tool plugins are the only
places that wire a specific set of engine jars onto the classpath (today: `wogu-temporal`),
so adding support for a new engine to an existing Maven/Gradle build is a one-line
dependency addition, not a code change.

```
wogu-maven-plugin / wogu-gradle-plugin
        │
        ├──> wogu-core   ──> wogu-api
        ├──> wogu-report ──> wogu-api
        └──> wogu-temporal ──> wogu-api      (future: wogu-conductor, wogu-camunda, ...)
```

## Rules, not validators

A single `WorkflowValidator` implementation commonly evaluates several rules in one pass
— sharing one parse of the source, one workflow scan, one call graph — because most of
that setup is identical across rules for the same engine. `Rule` (id, title, category,
severity, engine, since-version, documentation reference, auto-fix availability) and
`RuleResult` are what the report and future configuration key off of, not the validator
implementation. Every rule id is `WG###`, and each `RuleCategory` (Determinism,
Activities, Versioning, Signals, Updates, Performance, Best Practices, Security,
Organization Policies) reserves a fixed numeric range — enforced by `Rule`'s constructor,
not just documented (see [CONTRIBUTING.md](CONTRIBUTING.md#rule-numbering) for the exact
ranges).

## Adding a rule

Most rules need no Java at all. WG001–WG003 are all "flag this static method call"
rules, so each is a small YAML file under `wogu-temporal/src/main/resources/rules`,
executed by one generic `ForbiddenMethodRule`:

```yaml
id: WG002
type: forbidden-method
title: Thread.sleep() inside Workflow
category: Determinism
severity: ERROR
engine: Temporal Java SDK
since: 0.2.0
documentation: docs/rules/WG002.md
description: >-
  Thread.sleep() blocks the current worker thread. ...
replacement: >-
  Use Workflow.sleep(Duration) instead of Thread.sleep(). ...
methods:
  - java.lang.Thread.sleep
```

Adding another rule of this shape is: **drop a new YAML file in that directory** and
write its `docs/rules/WG0NN.md` (following the [WG001.md](docs/rules/WG001.md)
template) — nothing else. `RuleDefinitionLoader` scans the classpath for `rules/*.yaml`
at startup (works whether that's an exploded directory during a test run or packaged
inside the real plugin jar), so there's no filename to register anywhere, and neither
`wogu-core` nor `wogu-report` nor either build-tool plugin needs to change.

A rule that needs real analysis logic (a future workflow-complexity check, a
ContinueAsNew recommendation, versioning safety, activity configuration validation) is
the exception: it extends the `CustomRule` base class and is registered in
`TemporalWorkflowValidator`'s (currently empty) custom-rules list. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the full walkthrough of both paths, including how
to add support for an entirely new workflow engine.

## Building from source

```bash
mvn clean verify
```

builds and tests `wogu-api` through `wogu-maven-plugin`. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the full repository layout, how to build the
Gradle plugin and the samples, and how to run the intentionally-failing sample.

## Requirements

Java 17+, Maven 3.9+ or Gradle 8+, Temporal Java SDK (any reasonably recent version — all
three rules' detection is source-based and has no compile-time dependency on the SDK
itself; see [VERSIONING.md](VERSIONING.md)).

## Project status

Three rules, one workflow engine, and a reusable call-graph analysis engine behind all of
them. All three are declarative YAML definitions executed by one generic
`ForbiddenMethodRule` — none of them are hand-written Java classes anymore — without
touching `wogu-core`, `wogu-report`, or either build-tool plugin. The architecture —
SPI-based validator discovery, rules as first-class metadata independent of validator
implementation, an engine agnostic to any specific rule, a report renderer agnostic to
any specific engine — is designed to comfortably scale to 100+ rules and multiple
workflow engines the same way, with Java required only for the minority of rules that
need real analysis logic beyond a method-call pattern (see `CustomRule`). Configuration
(enabling/disabling specific rules, ignoring specific classes) is not implemented yet, but
every rule already has a stable, unique id to key that off of when it is.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please also read our
[Code of Conduct](CODE_OF_CONDUCT.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
