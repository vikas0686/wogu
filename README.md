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

Ten Determinism rules for the Temporal Java SDK, all reachable-from-a-workflow-entry-
point checks built on the same [call-graph analysis engine](#call-graph-analysis) — not
just direct usage inside the workflow implementation class, but calls (and
constructions) several methods away, and never a false positive from code inside an
Activity implementation (see [Call graph analysis](#call-graph-analysis)):

| Rule | Flags | Fix |
|---|---|---|
| [WG001](docs/rules/WG001.md) | `UUID.randomUUID()` | `Workflow.randomUUID()` |
| [WG002](docs/rules/WG002.md) | `Thread.sleep(...)` | `Workflow.sleep(Duration)` |
| [WG003](docs/rules/WG003.md) | `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()`, `OffsetDateTime.now()`, `ZonedDateTime.now()`, `Clock.systemUTC()`, `Clock.systemDefaultZone()` | `Workflow.currentTimeMillis()` |
| [WG004](docs/rules/WG004.md) | `Math.random()` | `Workflow.newRandom()` |
| [WG005](docs/rules/WG005.md) | `new Random()`, `Random.nextInt()/nextLong()/nextDouble()/nextBoolean()` | `Workflow.newRandom()` |
| [WG006](docs/rules/WG006.md) | `ThreadLocalRandom.current()` | `Workflow.newRandom()` |
| [WG007](docs/rules/WG007.md) | `new SecureRandom()`, `SecureRandom.nextBytes()/nextInt()` | `Workflow.newRandom()` (or an Activity, for genuine cryptographic randomness) |
| [WG008](docs/rules/WG008.md) | `System.getenv()` | Read config outside the workflow or via an Activity |
| [WG009](docs/rules/WG009.md) | `System.getProperty(...)` | Read config outside the workflow or via an Activity |
| [WG010](docs/rules/WG010.md) | `Executors.new*ThreadPool/Executor()`, `new Thread(...)`, `CompletableFuture.supplyAsync(...)`, `ForkJoinPool.commonPool()` | Temporal `Async` APIs |

Each is a self-contained addition to `TemporalWorkflowValidator`'s rule list — none of
them required a change to `wogu-core`, `wogu-report`, or either build-tool plugin (see
[Adding a rule](#adding-a-rule)).

## Quick start

### Maven

```xml
<plugin>
  <groupId>io.github.vikas0686</groupId>
  <artifactId>wogu-maven-plugin</artifactId>
  <version>0.1.0</version>
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
  id("io.wogu.wogu-gradle-plugin") version "0.1.0"
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
parameter, so every rule from WG002 through WG010 reuses the exact same traversal instead
of a new one-off scanner. `ForbiddenMethodRule` builds one
`io.wogu.temporal.callgraph.StaticMethodCallTarget` ("this call is a specific method,
matched syntactically by class/method name or, for an instance call like
`random.nextInt()` where the class name never appears at the call site, by resolving the
call and checking its declaring type") per entry in a rule's YAML `methods` list, and one
`io.wogu.temporal.callgraph.ConstructorCallTarget` ("this is a `new SomeClass(...)` call")
per entry in `constructors` — WG005's `new Random()` and WG010's `new Thread()` are the
same traversal finding a different kind of AST node. When a call can't be resolved to
project source (a third-party library, reflection, dynamic dispatch), traversal simply
stops there rather than guessing — WoGu prefers missing a violation behind an
unresolvable call over reporting a false positive.

The same traversal also stops, deliberately, at the boundary of a Temporal **Activity**
implementation: a call made through an Activity's interface (the normal way activities
are invoked) resolves to the interface's bodyless method and is a dead end on its own,
and `io.wogu.temporal.ActivityAwareness` additionally recognizes a method as
Activity-owned by its `@ActivityInterface`/`@ActivityMethod` annotations even when a
reference happens to be typed as the implementation class directly — so genuinely
non-deterministic code inside an Activity (which isn't replayed, and so isn't subject to
these rules) is never flagged, no matter how it's reached.

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
  wogu-temporal                Temporal Java SDK rules (WG001–WG010 today, all declared
                               as YAML under src/main/resources/rules and executed by the
                               generic ForbiddenMethodRule), the reusable CallGraphAnalyzer
                               engine, RuleRegistry/RuleDefinitionLoader,
                               WorkflowImplementationScanner, and ActivityAwareness (the
                               call-graph traversal boundary at an Activity
                               implementation), shared infrastructure for future
                               Temporal-specific rules.
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

Most rules need no Java at all. WG001–WG010 are all "flag this method call or
constructor" rules, so each is a small YAML file under
`wogu-temporal/src/main/resources/rules`, executed by one generic `ForbiddenMethodRule`:

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

A rule that also (or instead) needs to flag constructing a specific class adds a
`constructors` list of fully qualified class names alongside (or instead of) `methods` —
see WG005's `wg005.yaml`, which lists both `java.util.Random`'s instance methods under
`methods` and `java.util.Random` itself under `constructors` to catch both
`new Random()` and `randomInstance.nextInt()`.

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
ten rules' detection is source-based and has no compile-time dependency on the SDK
itself; see [VERSIONING.md](VERSIONING.md)).

## Project status

Ten rules, one workflow engine, and a reusable call-graph analysis engine behind all of
them. All ten are declarative YAML definitions executed by one generic
`ForbiddenMethodRule` — none of them are hand-written Java classes — without touching
`wogu-core`, `wogu-report`, or either build-tool plugin. The one generic rule type
already matches method calls (syntactically, or by resolution when the class name isn't
written at the call site) and constructors, and every rule's traversal stops at an
Activity implementation boundary, so this same declarative shape is expected to cover
most future rules too, not just this batch. The architecture — SPI-based validator
discovery, rules as first-class metadata independent of validator implementation, an
engine agnostic to any specific rule, a report renderer agnostic to any specific engine —
is designed to comfortably scale to 100+ rules and multiple workflow engines the same
way, with Java required only for the minority of rules that need real analysis logic
beyond a method-call/constructor pattern (see `CustomRule`). Configuration
(enabling/disabling specific rules, ignoring specific classes) is not implemented yet, but
every rule already has a stable, unique id to key that off of when it is.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please also read our
[Code of Conduct](CODE_OF_CONDUCT.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
