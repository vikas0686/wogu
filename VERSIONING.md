# Versioning Strategy

WoGu follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`).

## Lockstep versioning across modules

`wogu-api`, `wogu-core`, `wogu-temporal`, `wogu-report`, `wogu-maven-plugin`, and
`wogu-gradle-plugin` are released together, at the same version, from this one
repository. `wogu-maven-plugin` and `wogu-gradle-plugin` bundle the exact `wogu-core` /
`wogu-temporal` / `wogu-report` versions they were built against, so a project depending
on, say, `wogu-maven-plugin:1.2.0` always gets a consistent, tested combination — there is
no separate "engine version" to reconcile against a "plugin version."

This mirrors how JaCoCo and similar single-repository build-tooling projects version
their Ant/Maven/Gradle integrations alongside their core engine.

## What bumps which number

* **PATCH** (`0.1.0` → `0.1.1`): bug fixes, documentation, dependency bumps that don't
  change behavior, new validators that are purely additive (see below).
* **MINOR** (`0.1.0` → `0.2.0`): new validators, new configuration options, or other
  backwards-compatible additions to `wogu-api` (e.g. a new default method, a new optional
  field with a sensible default).
* **MAJOR**: any breaking change to `wogu-api` (a `WorkflowValidator` implementation that
  compiles against 1.x must keep compiling against 1.(x+1); breaking that requires a major
  bump), a validator's default severity changing from non-blocking to build-blocking (or
  vice versa) for existing code, or a change to the Mojo/task's default phase/lifecycle
  wiring.

**Adding a new validator is never itself a breaking change** — but note that if a new
validator finds real violations in an existing project, upgrading WoGu can turn a
previously green build red. This is expected (the same way upgrading a linter with new
rules enabled can) and will always be called out in the changelog entry for that
validator.

## Pre-1.0

While the project is at `0.x`, minor version bumps (`0.1.0` → `0.2.0`) may include small
breaking changes to `wogu-api` if needed to get the extension model right before
committing to long-term compatibility at `1.0.0`. These will always be called out
explicitly in [CHANGELOG.md](CHANGELOG.md).

## Compatibility baseline

Every release supports Java 17+, the Temporal Java SDK version pinned in the root
`pom.xml` (and reasonably recent adjacent versions, since `wogu-temporal`'s detection is
syntactic and has no compile-time dependency on the SDK itself), and current Maven 3.9+ /
Gradle 8+ releases.
