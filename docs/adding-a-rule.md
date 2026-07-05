# Adding a Rule

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
write its `docs/rules/WG0NN.md` (following the [WG001.md](rules/WG001.md)
template) — nothing else. `RuleDefinitionLoader` scans the classpath for `rules/*.yaml`
at startup (works whether that's an exploded directory during a test run or packaged
inside the real plugin jar), so there's no filename to register anywhere, and neither
`wogu-core` nor `wogu-report` nor either build-tool plugin needs to change.

A rule that needs real analysis logic (a future workflow-complexity check, a
ContinueAsNew recommendation, versioning safety, activity configuration validation) is
the exception: it extends the `CustomRule` base class and is registered in
`TemporalWorkflowValidator`'s (currently empty) custom-rules list. See
[CONTRIBUTING.md](../CONTRIBUTING.md) for the full walkthrough of both paths, including
how to add support for an entirely new workflow engine.

---

[← Back to README](../README.md)
