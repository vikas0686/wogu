# WoGu Rule Roadmap: Beyond Determinism

69 Temporal-native static analysis rules proposed for WoGu, organized into 15 categories and prioritized P0-P2. Each one is evaluated against one question: could a generic Java linter have found this, or does it require actually understanding how Temporal replays, schedules, and persists a workflow?

**Status: brainstorm, not yet implemented.** WG001-WG010 already ship in `wogu-temporal` (see [docs/rules/](rules/)); everything below is proposed. Tracked as individual issues on the [GitHub tracker](https://github.com/vikas0686/wogu/issues) (label `rule-proposal`).

WoGu already catches the class of bug every Temporal engineer learns about in week one: a direct call to `UUID.randomUUID()` or `Thread.sleep()` sitting inside workflow code. That's necessary, and WG001–WG010 do it well. But it's also the bug class every Temporal engineer learns to avoid fastest, precisely because it's the one every onboarding doc warns about.

The bugs that actually reach production at scale live one level deeper: a `getVersion()` checkpoint inserted in the wrong place, a signal handler still running when the workflow thinks it's done, a Query handler that quietly mutates state, an Activity with no timeout that turns a flaky downstream call into a stuck workflow for six months. None of these are "non-deterministic API call" bugs. All of them are Temporal-execution-model bugs — and none of them are things a generic Java static analyzer, or even a generic call-graph tool, has any chance of finding, because none of them are wrong in plain Java. They're only wrong once you know what `Workflow.await`, `ParentClosePolicy`, or an Update validator actually guarantees.

That's the brief below: 69 rules, grouped by the Temporal concept they protect, each one evaluated on whether it's real production value or just a restated best-practices-doc bullet point.

## At a glance

| Rules proposed | P0 — must have | P1 — high value | P2 — nice to have | Categories | WoGu-only differentiators |
|---|---|---|---|---|---|
| 69 | 22 | 30 | 17 | 15 | 16 |

## Categories

- [Determinism, extended](#determinism-extended) (WG011–WG015 · existing category, 5 rules)
- [Activity configuration](#activity-configuration) (WG100–WG106 · existing category, 7 rules)
- [Versioning & Worker Build IDs](#versioning--worker-build-ids) (WG200–WG204 · existing category, 5 rules)
- [Signals](#signals) (WG300–WG304 · existing category, 5 rules)
- [Queries](#queries) (WG730–WG733 · new category, 4 rules)
- [Updates](#updates) (WG350–WG353 · existing category, 4 rules)
- [Child workflows](#child-workflows) (WG700–WG704 · new category, 5 rules)
- [Cancellation](#cancellation) (WG750–WG753 · new category, 4 rules)
- [Async APIs, Selectors & Timers](#async-apis-selectors--timers) (WG770–WG774 · new category, 5 rules)
- [Nexus operations](#nexus-operations) (WG800–WG802 · new category, 3 rules)
- [Scheduling & Cron](#scheduling--cron) (WG820–WG822 · new category, 3 rules)
- [Performance & history optimization](#performance--history-optimization) (WG400–WG405 · existing category, 6 rules)
- [Observability](#observability) (WG850–WG853 · new category, 4 rules)
- [Security](#security) (WG600–WG603 · existing category, 4 rules)
- [Enterprise / org policy](#enterprise--org-policy) (WG900–WG904 · existing category, 5 rules)

## Determinism, extended

*WG011–WG015 · existing category*

> **Why it matters:** WG001–WG010 catch direct calls to known non-deterministic APIs. The next tier hides in how the escape hatches — SideEffect, MutableSideEffect, collection iteration order, object lifecycle — interact with replay. These survive a naive linter and still take production down.

### WG011 — I/O or blocking calls inside Workflow.sideEffect() — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** `Workflow.sideEffect(...)` is meant for a quick, local, non-deterministic computation whose result gets recorded once. Authors reach for it to "escape" determinism checks and put real I/O — an HTTP call, a DB read — inside the callback instead.

**Why it breaks Temporal.** The callback still runs synchronously on the workflow thread and blocks the workflow task. Unlike an Activity, it isn't retried independently, isn't heartbeated, and a failure inside it fails the whole workflow task rather than being handled as a scoped, retryable error. It looks like a determinism fix; it's actually an availability regression wearing one.

**Violating code:**

```java
String userTier = Workflow.sideEffect(String.class,
    () -> httpClient.get("/users/" + id + "/tier")); // network call, no retry, no timeout
```

**Recommended fix.** Move the call into an Activity with a real `ActivityOptions` timeout and retry policy. Reserve `sideEffect` for values like a locally-computed UUID or a coin-flip that need no I/O at all.

**Detection strategy.** Call Graph — traverse from every `Workflow.sideEffect`/`mutableSideEffect` lambda argument and flag any reachable call matching WoGu's existing I/O target list (network, file, reflection).

**False positive considerations.** Low. A callback that only does arithmetic or constructs a value from already-in-scope workflow state never trips this rule.

**Estimated implementation complexity.** Medium — reuses the existing call-graph engine with a new lambda-argument entry point.

### WG012 — MutableSideEffect with reference-equality value type

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** `Workflow.mutableSideEffect` records a new history event only when its `updateFunction` says the value changed. Passing a custom type that relies on default `Object.equals()` means "changed" is true on every single call, even when the logical value is identical.

**Why it breaks Temporal.** Every call appends a `MarkerRecorded` event to history. A mutableSideEffect that never converges silently inflates event count on every workflow task — the exact history-bloat failure mode the API exists to prevent.

**Violating code:**

```java
class Price { double amount; } // no equals()/hashCode()
Price p = Workflow.mutableSideEffect("price", Price.class,
    (o, n) -> !o.equals(n), () -> pricingService.currentPrice());
```

**Recommended fix.** Use a primitive, record, or a type with proper value-based `equals()`, or write an explicit field-by-field comparator instead of relying on `equals()`.

**Detection strategy.** Symbol Resolution — resolve the value type argument and check whether it overrides `equals()`/is a record/primitive.

**False positive considerations.** Medium. A deliberately reference-comparing updateFunction (rare, but valid for "always take the newest") should be suppressible.

**Estimated implementation complexity.** Medium.

### WG013 — Catching Throwable or Error in workflow code

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** Workflow code contains `catch (Throwable t)` or `catch (Error e)`.

**Why it breaks Temporal.** The SDK uses unchecked `Error` subclasses internally as control-flow signals — for cancellation, for the destroy path when a workflow task fails deterministically. Swallowing them breaks that internal control flow: a cancellation can silently fail to propagate, or a workflow task that should safely fail-and-retry instead limps forward in a corrupted state.

**Violating code:**

```java
try {
  activities.chargeCard(order);
} catch (Throwable t) {
  logger.warn("ignoring", t); // swallows Temporal's internal Error signals too
}
```

**Recommended fix.** Catch specific checked/application exceptions (`ActivityFailure`, your own business exceptions) — never `Throwable` or `Error` directly.

**Detection strategy.** AST — flag any `catch` clause whose type is `Throwable` or `Error` (or a superclass of a known Temporal internal `Error`) inside a workflow implementation.

**False positive considerations.** Very low — there is no legitimate reason to catch bare `Error` in workflow code.

**Estimated implementation complexity.** Low — pure AST pattern, no traversal needed.

### WG014 — Decision made from identity-hashed collection iteration order — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P2 | WARNING |

**Problem.** Workflow code iterates a `HashSet`/`HashMap` keyed by an object whose `hashCode()` is the default, identity-based one, and makes an externally-visible decision (which Activity to call first, in what order) based on that iteration order.

**Why it breaks Temporal.** Value-based hash keys iterate identically given identical insertion order and code — safe under replay. Identity-based hash codes depend on object allocation, which is not guaranteed stable across a JVM restart or a worker with different heap layout picking up the replay. It's the subtlest determinism bug in this catalog: legal Java, wrong under Temporal, and invisible to every tool that doesn't reason about which `hashCode()` implementation is actually in play.

**Violating code:**

```java
Set<Supplier> suppliers = new HashSet<>(candidateSuppliers); // Supplier: no equals/hashCode
for (Supplier s : suppliers) { activities.tryOrder(s); break; } // order not replay-stable
```

**Recommended fix.** Use `LinkedHashMap`/`LinkedHashSet` or a `List` whenever iteration order affects a workflow decision; if a hash-based collection is kept, give the key type value-based `equals()`/`hashCode()`.

**Detection strategy.** Data Flow — trace values read from a `Hash*`-typed collection to a call-graph target (Activity call, branch condition), then resolve the element type's `hashCode()` to confirm it's the inherited `Object` one.

**False positive considerations.** High if implemented naively — most HashMap usage is fine. Must confirm both (a) no custom hashCode and (b) iteration order actually reaches an observable decision, not just internal bookkeeping.

**Estimated implementation complexity.** High — needs data-flow tracing from collection to decision point, not just a call-graph match.

### WG015 — Non-deterministic work in a workflow constructor or field initializer

| Priority | Severity |
|---|---|
| P1 | ERROR |

**Problem.** A workflow implementation's constructor or instance field initializer calls a non-deterministic API (clock, random, I/O) instead of only the workflow's entry-point method.

**Why it breaks Temporal.** The workflow object is reconstructed from scratch on every replay, and its constructor runs again every time — WG001–WG010's entry-point scanning doesn't cover it, so this is a real coverage gap, not a stylistic nit.

**Violating code:**

```java
public class OrderWorkflowImpl implements OrderWorkflow {
  private final String startedAt = Instant.now().toString(); // runs on every replay
}
```

**Recommended fix.** Move any such computation into the workflow's entry-point method (still subject to WG001–WG010), or better, into an Activity.

**Detection strategy.** Call Graph — extend WG001–WG010's existing entry-point scanning to also treat the class's constructor and field initializers as roots.

**False positive considerations.** Low.

**Estimated implementation complexity.** Low — additional entry point into the existing engine, no new traversal logic.

---

## Activity configuration

*WG100–WG106 · existing category*

> **Why it matters:** An Activity call with no timeout, no retry ceiling, or the wrong execution mode isn't a style nit — it's an availability incident waiting for a slow dependency. Every rule here maps to a page a Temporal on-call has actually answered.

### WG100 — Activity stub built with no timeout set

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** `ActivityOptions` is built without `setStartToCloseTimeout` or `setScheduleToCloseTimeout`.

**Why it breaks Temporal.** Without either timeout the Activity can run effectively unbounded. A single hung downstream dependency turns into a workflow stuck for days, discovered only when someone asks "why hasn't this order shipped." This is, anecdotally, the single most common first production incident for teams new to Temporal.

**Violating code:**

```java
ActivityOptions options = ActivityOptions.newBuilder().build(); // no timeout at all
PaymentActivities a = Workflow.newActivityStub(PaymentActivities.class, options);
```

**Recommended fix.** Always set at least one of `startToCloseTimeout` or `scheduleToCloseTimeout`, sized to the Activity's real expected duration.

**Detection strategy.** Call Graph + Data Flow — trace the builder chain feeding `Workflow.newActivityStub`/`newLocalActivityStub` and check which setters were actually called.

**False positive considerations.** Low. A shared, pre-configured `ActivityOptions` constant defined elsewhere needs its own definition checked, not the call site — worth a suppression path for that pattern.

**Estimated implementation complexity.** Medium — builder-pattern data flow, including through shared constants.

### WG101 — Retry policy with no attempt ceiling and no schedule-to-close cap

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** `RetryOptions` leaves `maximumAttempts` unset (unlimited) while `ActivityOptions` also has no `scheduleToCloseTimeout`.

**Why it breaks Temporal.** Neither bound exists to stop retrying — a permanently-failing Activity (bad input, deleted downstream resource) retries forever, burning worker slots and task-queue throughput indefinitely instead of surfacing as a failure anyone can act on.

**Violating code:**

```java
RetryOptions.newBuilder().setBackoffCoefficient(2).build(); // maximumAttempts unset = infinite
```

**Recommended fix.** Set an explicit `maximumAttempts`, or a `scheduleToCloseTimeout` on the enclosing `ActivityOptions`, or both.

**Detection strategy.** Data Flow — same builder-chain analysis as WG100, cross-checking two sibling option objects together.

**False positive considerations.** Low-medium. Some Activities are intentionally retried forever with backoff (e.g. waiting on a human); allow an explicit suppression annotation/config for that documented case.

**Estimated implementation complexity.** Medium.

### WG102 — Activity input/output type not safely serializable

| Priority | Severity |
|---|---|
| P1 | ERROR |

**Problem.** An Activity method's signature includes a workflow-thread-only or inherently non-serializable type — `Promise`, a raw socket/connection, a `CancellationScope` — as a parameter or return type.

**Why it breaks Temporal.** Activity arguments and results are marshalled through the `DataConverter` across the workflow/Activity boundary (and often across processes). These types either fail to serialize at runtime or silently lose the semantics that made them useful in the first place.

**Violating code:**

```java
@ActivityInterface
public interface NotifyActivities {
  void notify(Promise<String> result); // Promise is workflow-thread-only
}
```

**Recommended fix.** Use plain data types (primitives, POJOs, records) for Activity signatures; resolve any `Promise` to its value before crossing the boundary.

**Detection strategy.** Symbol Resolution — check every `@ActivityMethod`/`@ActivityInterface` method's parameter and return types against a denylist of Temporal SDK workflow-only types.

**False positive considerations.** Low — this denylist is a fixed, small set of SDK types.

**Estimated implementation complexity.** Low.

### WG103 — Long Activity with no heartbeat and no heartbeat timeout

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** An Activity with a `startToCloseTimeout` of several minutes or more never calls `Activity.getExecutionContext().heartbeat(...)`, and no `heartbeatTimeout` is configured.

**Why it breaks Temporal.** Without a heartbeat, a worker crash mid-Activity is invisible until the full `startToCloseTimeout` elapses — often far longer than the failure needs to be detected, delaying retry and recovery.

**Violating code:**

```java
@Override
public void exportLargeReport(String id) {
  for (Chunk c : chunks(id)) { write(c); } // 20-minute activity, zero heartbeats
}
```

**Recommended fix.** Call `heartbeat()` periodically (e.g. once per processed chunk) and set a `heartbeatTimeout` shorter than `startToCloseTimeout`.

**Detection strategy.** Call Graph + AST — flag Activity implementations whose `startToCloseTimeout` (resolved from the calling `ActivityOptions`) exceeds a threshold and whose body contains no `heartbeat()` call.

**False positive considerations.** Medium — genuinely fast, non-chunkable Activities with a long timeout "for safety margin" don't need heartbeating; threshold should be tunable.

**Estimated implementation complexity.** Medium — must connect the Activity implementation back to the timeout configured at its call site(s).

### WG104 — Local Activity used for long-running or non-idempotent work

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A method invoked through `Workflow.newLocalActivityStub` does non-trivial I/O, runs longer than a few seconds, or is not safely re-runnable.

**Why it breaks Temporal.** Local Activities execute inline on the workflow task and, on workflow task retry, can re-execute in full — a non-idempotent Local Activity can double-run its side effect, and a slow one blocks the workflow task (and everything sticky-cached with it) for its whole duration.

**Violating code:**

```java
LocalActivities a = Workflow.newLocalActivityStub(LocalActivities.class, opts);
a.chargeCreditCard(order); // non-idempotent, network call, as a Local Activity
```

**Recommended fix.** Use a normal (non-local) Activity for anything with real I/O, non-trivial duration, or side effects that must not double-fire; reserve Local Activities for short, idempotent, low-latency operations.

**Detection strategy.** Call Graph — apply the same I/O target list used for WG011 to every method reachable from a Local Activity stub call.

**False positive considerations.** Medium — genuinely short local computations (a cache lookup) are the intended use case and shouldn't trip this.

**Estimated implementation complexity.** Medium.

### WG105 — Zero backoff coefficient on a high-frequency Activity

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** `backoffCoefficient(1.0)` combined with a short `initialInterval` and no `maximumInterval` cap, on an Activity called from inside a loop or by many concurrent workflow executions.

**Why it breaks Temporal.** Flat, fast retries against a struggling downstream dependency is exactly the thundering-herd pattern that turns a brief downstream blip into a sustained outage — and it's Temporal amplifying the problem via automatic retry rather than a manual retry loop a human would eventually notice and rate-limit.

**Violating code:**

```java
RetryOptions.newBuilder()
    .setBackoffCoefficient(1.0)
    .setInitialInterval(Duration.ofMillis(100))
    .build(); // no maximumInterval cap, flat 100ms retries forever
```

**Recommended fix.** Use an exponential `backoffCoefficient` (Temporal's default is 2.0) with an explicit `maximumInterval`.

**Detection strategy.** AST — inspect the literal values passed to `RetryOptions.Builder` setters.

**False positive considerations.** Medium — flat retry is occasionally intentional for very cheap, idempotent, low-volume calls; should be configurable per organization rather than hardcoded as always-wrong.

**Estimated implementation complexity.** Low.

### WG106 — High-frequency Activity call site with an uncapped retry policy

| Priority | Severity |
|---|---|
| P2 | WARNING |

**Problem.** An Activity stub call sits inside a loop with a large or unbounded iteration count, and its retry policy has no `maximumAttempts`/`scheduleToCloseTimeout` ceiling.

**Why it breaks Temporal.** This is WG101's failure mode multiplied by call frequency: a failure storm across thousands of loop iterations, each retrying unboundedly, can explode event-history size far faster than a single unbounded Activity call ever would.

**Violating code:**

```java
for (Item item : order.items()) { // hundreds of items
  activities.reserveInventory(item); // uncapped retry, called per-item
}
```

**Recommended fix.** Cap retries per call, and/or batch the loop body into a single Activity call that processes the whole collection.

**Detection strategy.** Call Graph + CFG — combine WG101's retry-policy check with loop-nesting depth from the control flow graph around the call site.

**False positive considerations.** Medium — short, bounded loops (a handful of items) don't carry the same risk as genuinely large collections.

**Estimated implementation complexity.** High — needs a real CFG, not just call-graph reachability, to reason about loop nesting.

---

## Versioning & Worker Build IDs

*WG200–WG204 · existing category*

> **Why it matters:** `getVersion()` is the single API most responsible for production `NonDeterministicException`s industry-wide — and the hardest to get right by hand, because the mistake stays invisible until a worker replays an in-flight execution days after the deploy that introduced it.

### WG200 — Code path removed before old executions could have completed

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** A `Workflow.getVersion(...)` branch's old (pre-change) code path is deleted in the same or a near-immediate follow-up change, rather than being retained until every workflow execution that could still be replaying it has completed.

**Why it breaks Temporal.** An in-flight execution started before the deploy replays from the beginning of its history using the *current* code — if the old branch is gone, that replay hits the new code at a point history says it shouldn't be, and throws `NonDeterministicException`, the single most common Temporal production incident.

**Violating code:**

```java
// Deploy N: adds the version check
int v = Workflow.getVersion("addDiscount", Workflow.DEFAULT_VERSION, 1);
if (v == Workflow.DEFAULT_VERSION) { legacyPricing(); } else { newPricing(); }
// Deploy N+1, same week: legacyPricing() branch deleted — still-running v0 executions break
```

**Recommended fix.** Keep the old branch until telemetry confirms no running execution predates the version marker (Workflow history retention, or a tracked "oldest open execution" query), then remove it in its own deploy.

**Detection strategy.** AST + Git-history heuristic — flag a diff that deletes a branch guarded by an existing `Workflow.getVersion` call within a configurable "too soon" window of the change that introduced the guard.

**False positive considerations.** Medium — legitimately safe if the org can prove no old execution is in flight; needs a documented override, not a silent pass.

**Estimated implementation complexity.** High — requires reasoning across commits, not a single-snapshot AST check.

### WG201 — getVersion() called conditionally instead of unconditionally

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** A `Workflow.getVersion(...)` call sits inside an `if` whose condition depends on non-deterministic or version-dependent state, instead of being evaluated the same way on every code path that reaches that point.

**Why it breaks Temporal.** Every replay must record the exact same sequence of `getVersion` markers in the exact same order; making the call itself conditional on something that can differ between the original run and a replay reintroduces the non-determinism the API exists to prevent.

**Violating code:**

```java
if (order.total() > threshold) { // threshold could change between runs
  int v = Workflow.getVersion("bigOrderFlow", Workflow.DEFAULT_VERSION, 1);
}
```

**Recommended fix.** Call `getVersion` unconditionally at the start of the code region it guards, then branch on *its return value*, not on business logic gating the call itself.

**Detection strategy.** CFG — check whether every path through the enclosing method that could reach this code region also reaches the `getVersion` call.

**False positive considerations.** Low-medium — some conditions genuinely are replay-stable (a value fixed at workflow start); needs care to avoid over-flagging.

**Estimated implementation complexity.** High — real control-flow-graph reachability analysis.

### WG202 — New command inserted behind an existing getVersion() checkpoint without a new branch — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** A new Activity/Signal/Timer call is added directly inside code already gated by an existing, already-shipped `getVersion` checkpoint, without introducing its own new version branch.

**Why it breaks Temporal.** This is the single most common real-world cause of a post-deploy `NonDeterministicException`: any change to the sequence of commands Temporal records (a new Activity call, a reordered await) changes the expected event sequence for every execution replaying through that point, whether or not it's wrapped in a version check that was already satisfied before this change.

**Violating code:**

```java
if (Workflow.getVersion("shipping", DEFAULT_VERSION, 1) >= 1) {
  activities.reserveInventory(order);
  activities.notifyWarehouse(order); // added later, same branch, no new getVersion call
}
```

**Recommended fix.** Wrap the new call in its own `getVersion("shipping-v2", ...)` checkpoint, nested inside the existing branch.

**Detection strategy.** Call Graph + Git-diff — detect a new call-graph edge (Activity/Signal/Timer) added inside a region already dominated by an existing `getVersion` branch, without an accompanying new `getVersion` call in the same diff.

**False positive considerations.** Medium — a genuinely additive, order-independent change (e.g. a log line, a query-only read) is safe; the rule needs to distinguish history-recorded commands from no-op statements.

**Estimated implementation complexity.** Very High — combines call-graph diffing across two revisions with dominance analysis. Highest-value rule in the whole catalog if it can be built reliably.

### WG203 — Heavy getVersion() patch debt with no Worker Build ID migration

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A workflow type accumulates a large number of `getVersion` checkpoints over time with no adoption of Worker Build ID–based versioning (Worker Deployment Versions) for the parts of the rollout that don't need mid-execution patching.

**Why it breaks Temporal.** It's not incorrect, but it's a maintenance and risk signal: every additional `getVersion` branch is another place WG201/ WG202 can be violated. Build ID pinning removes the need for a patch branch for changes that only need to apply to *new* executions.

**Violating code:**

```java
// 14 getVersion() checkpoints accumulated in one workflow class over 2 years
```

**Recommended fix.** Evaluate migrating new-executions-only changes to Worker Build ID pinning/ramping instead of another `getVersion` branch.

**Detection strategy.** AST — count distinct `getVersion` change IDs per workflow class against a configurable threshold.

**False positive considerations.** Low, but advisory rather than a hard defect — purely a complexity signal.

**Estimated implementation complexity.** Low.

### WG204 — New Build ID promoted to default without a pinned or ramped rollout

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** Deployment configuration sets a new Worker Build ID as the default for a task queue directly, with no prior pinned/ramping rollout stage.

**Why it breaks Temporal.** Same risk class as any all-at-once deploy — a latent determinism bug in the new build now affects every new workflow execution immediately, with no ramped blast-radius limit.

**Violating code:**

```java
# deploy.yaml: sets default build id directly, no ramp stage
worker_build_id: "v47"
rollout: default
```

**Recommended fix.** Use a ramped rollout (percentage-based) before promoting a Build ID to full default, matching how the org already treats other production rollouts.

**Detection strategy.** Config Analysis — this one is honestly out of scope for source-code AST; it requires reading deployment/CI configuration, not workflow source. Included for completeness, flagged as a stretch item.

**False positive considerations.** N/A at the source level — this is a process rule, not a code defect.

**Estimated implementation complexity.** Very High — needs a CI/deployment-config integration WoGu doesn't have today, not just a new AST rule.

---

## Signals

*WG300–WG304 · existing category*

> **Why it matters:** Signals are the one place a workflow's external contract and its internal state directly collide. Get the handshake wrong and you get a workflow that appears to complete cleanly while a handler is still running underneath it.

### WG300 — Workflow completes while a signal handler is still logically in-flight — ★★ FLAGSHIP DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** The workflow's main method can return while async work triggered by a `@SignalMethod` handler (an Activity call, a spawned coroutine) is still running, because nothing gates completion on that work finishing.

**Why it breaks Temporal.** The SDK actually warns about this exact pattern at runtime ("workflow finished while a long-running handler is running") because a signal handler doesn't get to finish once the workflow execution closes — any partially-applied side effect from it is left in an undefined state, and the client-visible workflow result may not reflect work the caller reasonably expected to have completed.

**Violating code:**

```java
private boolean processing = false;
@SignalMethod public void onPayment(Payment p) {
  processing = true;
  activities.recordPayment(p); // async work with nothing gating workflow completion on it
  processing = false;
}
@Override public String run() {
  return Workflow.await(Duration.ofMinutes(30), () -> done); // doesn't wait for `processing`
}
```

**Recommended fix.** Gate the workflow's completion condition on an explicit in-flight-handler counter: `Workflow.await(() -> done && inFlightHandlers == 0)`.

**Detection strategy.** Data Flow — trace whether any boolean/counter field mutated inside a `@SignalMethod` body is referenced in the predicate passed to the `Workflow.await` call that gates the workflow's return.

**False positive considerations.** Medium — fire-and-forget signal handlers are sometimes intentional (e.g. best-effort logging); needs a way to mark a handler as deliberately not completion-gated.

**Estimated implementation complexity.** Very High — cross-method data flow between a signal handler and the completion predicate. The single strongest differentiator candidate in this catalog.

### WG301 — Signal handler performs blocking work directly instead of scheduling it

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A `@SignalMethod` body calls an Activity and blocks on its result synchronously (e.g. via `Promise.get()`) rather than kicking off async work and returning quickly.

**Why it breaks Temporal.** Signal handlers are meant to be quick, interrupt-like updates to workflow state. Blocking inside one holds up the workflow coroutine's ability to process other signals/timers/the main flow until it resolves, and complicates reasoning about what state the workflow is in mid-handler.

**Violating code:**

```java
@SignalMethod public void onCancelRequest() {
  activities.notifyDownstream().get(); // blocks the signal handler synchronously
}
```

**Recommended fix.** Set a flag or enqueue work inside the handler; perform the actual Activity call from the main workflow coroutine, gated by `Workflow.await`.

**Detection strategy.** Call Graph — flag any Activity call or `Promise.get()` reachable directly from a `@SignalMethod`.

**False positive considerations.** Medium — a short, fast Activity called this way is not necessarily wrong; severity should scale with the Activity's own configured timeout.

**Estimated implementation complexity.** Medium.

### WG302 — Signal handler with no duplicate-delivery guard

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A signal handler applies its payload directly with no idempotency check (a processed-IDs set, a monotonic sequence check).

**Why it breaks Temporal.** Temporal delivers each *sent* signal exactly once, but a caller retrying its own `signalWorkflow` call after a network timeout can legitimately send the same logical signal twice — a non-obvious, SDK-documented pitfall that surprises most teams the first time it happens.

**Violating code:**

```java
@SignalMethod public void addFunds(String txId, double amount) {
  balance += amount; // no check against txId already having been applied
}
```

**Recommended fix.** Track applied idempotency keys (e.g. a bounded set of recent transaction IDs) and no-op on a repeat.

**Detection strategy.** AST + Data Flow — flag a signal handler whose parameters include what looks like an idempotency key (an id/UUID-typed parameter) that is never checked against prior state before a mutation.

**False positive considerations.** High — heuristic-based ("looks like an id parameter") without deep semantic understanding of intent; best shipped as an INFO nudge, not an ERROR.

**Estimated implementation complexity.** High.

### WG303 — Unbounded signal accumulation with no drain-and-ContinueAsNew

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A workflow appends every received signal to an in-memory list/queue with no upper bound and no periodic `ContinueAsNew` to reset history.

**Why it breaks Temporal.** Every signal is its own history event; a long-lived "entity" workflow (per-customer, per-account) accepting unbounded signal volume over its lifetime is the most common on-ramp to history-size limits — see WG400.

**Violating code:**

```java
private final List<Event> events = new ArrayList<>(); // grows forever
@SignalMethod public void onEvent(Event e) { events.add(e); }
```

**Recommended fix.** Periodically snapshot and `ContinueAsNew` with a summarized/truncated state once the queue or event count crosses a threshold.

**Detection strategy.** Data Flow — flag a collection field mutated only by `add`/`put` from signal handlers, with no reachable `Workflow.continueAsNew` call anywhere in the class.

**False positive considerations.** Medium — short-lived workflows with naturally bounded signal volume don't need this.

**Estimated implementation complexity.** Medium.

### WG304 — Conditionally-registered dynamic signal handler

| Priority | Severity |
|---|---|
| P2 | ERROR |

**Problem.** `Workflow.registerListener` (or dynamic signal registration) is called from inside a branch that depends on non-deterministic or runtime-varying state, rather than unconditionally and identically on every replay.

**Why it breaks Temporal.** Dynamic handler registration is itself a replay-sensitive operation; registering a handler on one execution/replay and not another is a determinism hazard structurally identical to WG201's conditional `getVersion` call, but for handler wiring instead of a version branch.

**Violating code:**

```java
if (featureFlagService.isEnabled("newSignals")) { // non-deterministic condition
  Workflow.registerListener(new ExtraSignalHandler());
}
```

**Recommended fix.** Register all dynamic listeners unconditionally at workflow start; branch on business logic *inside* the handler instead of around its registration.

**Detection strategy.** CFG — same reachability analysis style as WG201, applied to `registerListener` call sites.

**False positive considerations.** Low — dynamic listener registration is already rare, so most matches are genuine.

**Estimated implementation complexity.** Medium.

---

## Queries

*WG730–WG733 · new category*

> **Why it matters:** A Query looks like a free read of workflow state. The SDK doesn't stop a Query handler from doing anything a normal method can do — which means query purity is a convention, not a compiler constraint, and violating it is invisible until a client gets an inconsistent answer or a hung request.

### WG730 — Query handler mutates workflow state — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** A `@QueryMethod` assigns to an instance field or otherwise changes workflow-visible state rather than only reading it.

**Why it breaks Temporal.** Queries are meant to be pure, side-effect-free reads answered from already-recorded state — nothing about them is meant to be recorded in history. A mutating query produces state changes that never went through the workflow's normal execution/replay path, meaning that state can silently diverge between what history says happened and what the workflow object actually holds.

**Violating code:**

```java
@QueryMethod public int getAndResetErrorCount() {
  int c = errorCount;
  errorCount = 0; // mutation inside a query handler
  return c;
}
```

**Recommended fix.** Make the query strictly read-only; if a "reset" semantic is actually needed, expose it as a Signal or Update instead, which go through the normal recorded execution path.

**Detection strategy.** AST + Data Flow — flag any field assignment reachable directly from a `@QueryMethod` body.

**False positive considerations.** Low — mutation inside a query is essentially never intentional and correct.

**Estimated implementation complexity.** Medium — reuses call-graph traversal already built for signal-handler analysis, rooted at query methods instead.

### WG731 — Query handler calls a blocking Temporal API

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** A `@QueryMethod` body calls an Activity stub method, `Promise.get()`, or `Workflow.await(...)`.

**Why it breaks Temporal.** Queries must answer synchronously from state the workflow already has; they cannot yield the workflow coroutine. A blocking call inside one will fail outright or hang the query response, not "eventually resolve."

**Violating code:**

```java
@QueryMethod public String getLatestStatus() {
  return activities.fetchStatus(); // blocking Activity call from a query
}
```

**Recommended fix.** Only read already-in-memory workflow state from a query; keep that state fresh via the normal signal/Activity flow, not on-demand at query time.

**Detection strategy.** Call Graph — flag any Activity stub call or blocking Temporal API reachable from a `@QueryMethod` entry point.

**False positive considerations.** Very low.

**Estimated implementation complexity.** Low — same engine as WG730, different target list.

### WG732 — Query handler leaks an internal exception type to callers

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A query handler lets an internal/implementation-detail exception (a raw NPE, an internal service exception type) propagate to the caller instead of a well-defined, serializable application exception.

**Why it breaks Temporal.** Query callers are often external clients; a leaked internal exception surfaces implementation details (class names, stack traces) across that boundary the same way an unhandled 500 would in a public API.

**Violating code:**

```java
@QueryMethod public Order getOrder(String id) {
  return orderMap.get(id); // NPE if absent, leaks straight to the query caller
}
```

**Recommended fix.** Return an `Optional`/nullable result or throw a well-defined, documented application exception for "not found."

**Detection strategy.** AST — flag query handlers with no explicit null-check/bounds-check before returning a map/collection lookup result directly.

**False positive considerations.** High — many "impossible" NPEs really are impossible given the workflow's own invariants; best as an INFO-level nudge.

**Estimated implementation complexity.** Low.

### WG733 — Query return type changed without a compatibility note

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A `@QueryMethod`'s return type or name changes between commits with no changelog/compatibility annotation.

**Why it breaks Temporal.** Unlike workflow determinism, a query signature change doesn't break replay — but it silently breaks every external caller (UI, CLI tooling, other services) still expecting the old shape, with no compiler or runtime signal that a contract changed.

**Violating code:**

```java
// before: @QueryMethod String getStatus();
// after:  @QueryMethod StatusDetails getStatus(); // silent breaking change for callers
```

**Recommended fix.** Add a new query name for a breaking shape change, or document the break explicitly in the changelog and coordinate caller migration.

**Detection strategy.** Git-diff AST — compare `@QueryMethod` signatures between revisions.

**False positive considerations.** Low, but this is an API-compatibility lint, not a correctness bug — scope and severity should reflect that.

**Estimated implementation complexity.** Medium — needs two-revision comparison like WG202/WG733's git-diff cousins.

---

## Updates

*WG350–WG353 · existing category*

> **Why it matters:** Update is Temporal's newest core primitive, and its validator/handler split is exactly the kind of two-phase contract engineers get subtly wrong: which phase should reject, which phase should mutate, and what happens to history either way.

### WG350 — Validation logic implemented in the handler instead of the validator — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** An `@UpdateMethod` handler throws to reject invalid input, rather than that check living in the paired `@UpdateValidator` method.

**Why it breaks Temporal.** A validator rejection is *not* recorded in workflow history at all — the client sees a clean rejection with no history footprint. A handler-level throw happens after the update has already been accepted into history, so the workflow now carries a recorded-but-failed update event forever, and the client sees a different failure semantic (workflow update failed, not update rejected) than the author almost certainly intended.

**Violating code:**

```java
@UpdateMethod public void setLimit(int limit) {
  if (limit < 0) throw new IllegalArgumentException("negative"); // should be in the validator
  this.limit = limit;
}
```

**Recommended fix.** Move every input-validity check into the matching `@UpdateValidator` method; keep the handler itself unconditional given validated input.

**Detection strategy.** AST + Symbol Resolution — pair each `@UpdateMethod` with its `@UpdateValidator` and flag parameter-derived `if (...) throw` guards found in the handler that have no equivalent check in the validator.

**False positive considerations.** Medium — some handler-level failures are genuinely about runtime state, not input validity, and legitimately belong in the handler.

**Estimated implementation complexity.** High — requires matching validator/handler pairs and comparing their guard conditions.

### WG351 — Update validator has side effects

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** An `@UpdateValidator` method mutates workflow state, calls an Activity, or otherwise does more than a synchronous read-only check.

**Why it breaks Temporal.** The same purity contract as a Query (WG730), but for the gatekeeper half of Update — a validator is meant purely to accept or reject, and since a rejection means the call never happened as far as history is concerned, any side effect performed there can execute inconsistently between an accepted and a rejected outcome.

**Violating code:**

```java
@UpdateValidator public void validateSetLimit(int limit) {
  auditLog.add("validation attempt: " + limit); // side effect in the validator
  if (limit < 0) throw new IllegalArgumentException();
}
```

**Recommended fix.** Keep validators to pure reads of current workflow state plus the update's own arguments; move any logging/side effect into the handler, which only runs on acceptance.

**Detection strategy.** AST + Data Flow — same mutation/blocking-call detection as WG730/WG731, rooted at `@UpdateValidator` methods.

**False positive considerations.** Low.

**Estimated implementation complexity.** Medium — reuses WG730's engine against a new entry point.

### WG352 — Workflow can complete before an in-flight update returns to its caller

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** The workflow's completion condition doesn't account for an Update handler that's still executing (e.g. awaiting an Activity) when the main method's return condition becomes true.

**Why it breaks Temporal.** Structurally the same risk as WG300 for signals, but for Updates specifically — an Update caller is typically waiting synchronously for a result, so a workflow completing out from under an in-flight handler is a materially worse experience for that caller than for a fire-and-forget signal.

**Violating code:**

```java
@UpdateMethod public String processRefund(Refund r) {
  return activities.issueRefund(r); // long-running, not counted in completion gating
}
```

**Recommended fix.** Gate workflow completion on an in-flight-update counter, the same pattern as WG300's fix for signals.

**Detection strategy.** Data Flow — same cross-method analysis as WG300, rooted at `@UpdateMethod` instead of `@SignalMethod`.

**False positive considerations.** Medium — newer SDK versions increasingly guard this automatically; the rule's value depends on which SDK version a project pins, and should be version-aware.

**Estimated implementation complexity.** Very High — same engine as WG300, plus SDK-version-aware suppression.

### WG353 — Concurrent updates race on shared state with no serialization

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** Two or more Update handlers read-then-write the same field across an await point (an Activity call inside the handler) with nothing preventing a second update from interleaving between the read and the write.

**Why it breaks Temporal.** Unlike a plain workflow method, Updates can genuinely execute concurrently with each other once one yields — this is a real, Update-API-specific concurrency model most engineers coming from a single-threaded mental model of "workflow code" don't expect, and it's a fresh source of classic check-then-act races inside code that looks sequential.

**Violating code:**

```java
@UpdateMethod public void reserve(int amount) {
  int current = balance; // read
  activities.log(amount); // yields — another reserve() can interleave here
  balance = current - amount; // write based on stale read
}
```

**Recommended fix.** Use a workflow-scoped mutex pattern (`Workflow.await` gating on a busy flag) around the critical section, or restructure to avoid yielding between the read and the write.

**Detection strategy.** Data Flow + CFG — detect a field read before an await point and a write to the same field after it, within an `@UpdateMethod`, with no mutex-style guard.

**False positive considerations.** High — this is genuinely hard to prove without false positives; best shipped as a WARNING nudge with a clear explanation, not a build-blocking ERROR.

**Estimated implementation complexity.** Very High.

---

## Child workflows

*WG700–WG704 · new category*

> **Why it matters:** A child workflow is a second workflow with its own lifecycle, its own failure semantics, and a parent-close policy that decides its fate the moment the parent exits — decisions best made deliberately, not left to a default.

### WG700 — Fire-and-forget child workflow left on the default ParentClosePolicy — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** A child workflow is started via `Async.function(childStub::run)` and its result `Promise` is never stored or awaited, while `ChildWorkflowOptions` leaves `ParentClosePolicy` at its default (`PARENT_CLOSE_POLICY_TERMINATE`).

**Why it breaks Temporal.** The moment the parent workflow completes, every child left on the default policy is terminated — including one the author clearly intended to outlive the parent, since they never even kept a handle to await it. This is a silent, delayed failure: the child appears to work fine in every short-lived test and dies for real the first time a parent actually completes in production.

**Violating code:**

```java
ReportWorkflow child = Workflow.newChildWorkflowStub(ReportWorkflow.class);
Async.function(child::generateMonthlyReport); // Promise dropped; policy still TERMINATE
```

**Recommended fix.** Set `ParentClosePolicy.ABANDON` explicitly for any child meant to survive its parent, and document that choice — or await the child properly if it was actually meant to be synchronous.

**Detection strategy.** Call Graph + Data Flow — flag a child-workflow stub call whose result `Promise` is never assigned/awaited, cross-referenced with the `ChildWorkflowOptions` used to build the stub.

**False positive considerations.** Low-medium — a child genuinely meant to terminate with its parent (a scoped helper) is a legitimate, common pattern and shouldn't be flagged just for being unawaited.

**Estimated implementation complexity.** High — needs to connect the stub's options object to its call site and the call's result usage.

### WG701 — Child workflow start not distinguished from child workflow completion

| Priority | Severity |
|---|---|
| P2 | WARNING |

**Problem.** Code treats the `Promise` returned by `Async.function` for a child workflow as proof the child *started*, when only `Promise.get()` resolving proves it *completed* — the separate "child started" signal (`Workflow.getWorkflowExecution(stub)`) is never checked.

**Why it breaks Temporal.** A child workflow can fail to even start (e.g. a workflow-ID collision) distinctly from failing after starting; code that only branches on the completion `Promise` can't tell these apart, and may retry or compensate incorrectly for a child that never ran at all versus one that ran and failed.

**Violating code:**

```java
Promise<Void> result = Async.function(child::run);
result.get(); // catches completion failures, never distinguishes "never started"
```

**Recommended fix.** Await `Workflow.getWorkflowExecution(stub)` to confirm the child started before treating the completion `Promise`'s failure as a "ran and failed" case in compensation logic.

**Detection strategy.** Call Graph — flag child-workflow invocations whose only handled signal is the completion `Promise`.

**False positive considerations.** High — most workflows genuinely don't need this distinction; best kept advisory (INFO/P2), applicable mainly where compensation logic branches on child failure.

**Estimated implementation complexity.** Medium.

### WG702 — Unbounded recursive child-workflow spawning

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A workflow type starts a child workflow of the *same* type from within its own execution, with no depth counter or termination bound.

**Why it breaks Temporal.** A logic bug that fails to terminate the recursion turns into unbounded workflow fan-out against the task queue and cluster — a "workflow bomb" that's far harder to stop mid-flight than a runaway loop inside a single process, since each spawned execution is independently running and independently recursing.

**Violating code:**

```java
if (needsMoreWork()) {
  Async.function(Workflow.newChildWorkflowStub(SameWorkflow.class)::run); // no depth bound
}
```

**Recommended fix.** Thread an explicit depth/iteration count through the recursive call and hard-stop at a configured maximum.

**Detection strategy.** Call Graph — detect a workflow type invoking a child workflow stub of its own type, then check for a bounding condition in the reachable guard.

**False positive considerations.** Medium — some self-recursive patterns are correctly bounded by external state (a queue draining to empty) the analyzer can't fully verify.

**Estimated implementation complexity.** High.

### WG703 — Child workflow used where ContinueAsNew was the right tool — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A workflow spawns a child of the same type, with the same task queue, purely to "reset" and keep looping — the same shape `Workflow.continueAsNew` exists for — instead of calling it directly.

**Why it breaks Temporal.** Spawning a child for this adds a whole second workflow execution's worth of overhead and a parent/child relationship (with its own `ParentClosePolicy` and failure-propagation semantics) to solve a problem `ContinueAsNew` solves natively, in-place, with a clean history reset and none of the extra failure modes above.

**Violating code:**

```java
if (history.size() > threshold) {
  Async.function(Workflow.newChildWorkflowStub(PollingWorkflow.class)::run, state);
  return; // should have been Workflow.continueAsNew(state) instead
}
```

**Recommended fix.** Use `Workflow.continueAsNew(...)` for same-type "keep going with fresh history" patterns; reserve child workflows for genuinely separate units of work.

**Detection strategy.** Call Graph — flag a child-workflow stub whose type matches the enclosing workflow's own type, invoked as the terminal action before return.

**False positive considerations.** Medium — a same-type child workflow is occasionally intentional (e.g. deliberately isolating retries as separate, independently-visible executions).

**Estimated implementation complexity.** Medium.

### WG704 — No cancellation propagation policy set for a child workflow

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** `ChildWorkflowCancellationType` is left at its default rather than chosen deliberately for a child performing meaningful work.

**Why it breaks Temporal.** The default cancellation type (`WAIT_CANCELLATION_COMPLETED`) blocks the parent's own cancellation from completing until the child acknowledges — fine for most cases, but a silent surprise for a child that's slow to react to cancellation and unexpectedly stalls the parent's shutdown.

**Violating code:**

```java
ChildWorkflowOptions.newBuilder().build(); // cancellation type never considered
```

**Recommended fix.** Set `ChildWorkflowCancellationType` explicitly based on whether the parent should wait for the child's graceful shutdown or move on immediately.

**Detection strategy.** AST — check whether `setCancellationType` was called on the builder.

**False positive considerations.** Low, but this is a soft advisory — the default is reasonable for most workflows.

**Estimated implementation complexity.** Low.

---

## Cancellation

*WG750–WG753 · new category*

> **Why it matters:** Cancellation in Temporal is cooperative and scoped, and Termination isn't cancellation at all. Code that only handles one of the two failure modes looks correct in every test and then loses a compensating action in production.

### WG750 — No CanceledFailure handling around compensable work

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A workflow performs multi-step work with real-world side effects (charges, reservations) and never catches `CanceledFailure` anywhere to run compensating actions.

**Why it breaks Temporal.** A cancellation request propagates as a `CanceledFailure` through awaited calls; with nothing catching it, partial side effects already committed (an inventory hold, a partial charge) are left dangling with no rollback.

**Violating code:**

```java
activities.reserveInventory(order);
activities.chargeCard(order); // no try/catch(CanceledFailure) anywhere to release the reservation
```

**Recommended fix.** Wrap compensable sequences in `try { ... } catch (CanceledFailure e) { compensate(); throw e; }` (a Saga pattern), releasing or reversing already-committed side effects.

**Detection strategy.** Call Graph — flag a sequence of two or more Activity calls with real-world side effects (heuristically: mutating verbs like charge/reserve/book) inside a method with no reachable `catch (CanceledFailure ...)`.

**False positive considerations.** High — "does this Activity have a real-world side effect" is a naming heuristic at best; ship as an advisory nudge, not a hard rule.

**Estimated implementation complexity.** High.

### WG751 — Activity never checks for cancellation on the worker side — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A long-running Activity implementation heartbeats (satisfying WG103) but never checks `Activity.getExecutionContext().isCancelRequested()`, so it keeps working even after the workflow has requested its cancellation.

**Why it breaks Temporal.** Workflow-side cancellation intent only actually stops in-flight work if the Activity implementation itself polls for it — this is a genuinely cross-boundary bug: the workflow code that requests cancellation looks completely correct, and the Activity code that ignores it looks completely correct in isolation too. Only reasoning across both sides of the boundary catches it, which is exactly the boundary WoGu's call-graph engine currently treats as opaque by design.

**Violating code:**

```java
@Override public void exportReport(String id) {
  for (Chunk c : chunks(id)) {
    Activity.getExecutionContext().heartbeat(c.index()); // heartbeats, but...
    write(c); // ...never checks isCancelRequested() to actually stop
  }
}
```

**Recommended fix.** Check `isCancelRequested()` at the same granularity as the heartbeat and exit the loop (throwing to fail the Activity cleanly) once true.

**Detection strategy.** Call Graph — a deliberate, narrow exception to WoGu's Activity-boundary rule: for this one check, follow into the Activity implementation reachable from a heartbeating call site and confirm a paired `isCancelRequested()` check exists.

**False positive considerations.** Medium — some Activities are intentionally uninterruptible once started (an atomic external transaction); needs a suppression path for that case.

**Estimated implementation complexity.** Very High — the only rule in this catalog that requires deliberately crossing the Activity boundary WoGu otherwise treats as a hard stop.

### WG752 — Cleanup work launched inside the scope that's already being cancelled

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** Compensation/cleanup Activities triggered from inside a `catch (CanceledFailure ...)` block are started in the same (already cancelling) `CancellationScope` rather than a detached one.

**Why it breaks Temporal.** Anything launched inside a scope that's already being cancelled is itself immediately subject to that same cancellation — the cleanup Activity meant to run *because of* the cancellation gets cancelled before it can run, silently skipping the compensation the author was trying to guarantee.

**Violating code:**

```java
try {
  activities.chargeCard(order);
} catch (CanceledFailure e) {
  activities.refund(order); // still inside the cancelling scope — gets cancelled too
  throw e;
}
```

**Recommended fix.** Wrap cleanup work in `Workflow.newDetachedCancellationScope(() -> activities.refund(order)).run()` so it survives the outer cancellation.

**Detection strategy.** AST + Call Graph — flag an Activity call inside a `catch (CanceledFailure ...)` block not wrapped in `Workflow.newDetachedCancellationScope`.

**False positive considerations.** Low — this pattern is unambiguous once `CanceledFailure` handling exists at all.

**Estimated implementation complexity.** Medium.

### WG753 — Saga-style compensation with no acknowledgment that Terminate bypasses it

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A workflow implements a full compensation/Saga pattern for cancellation but the codebase has no documented answer for what happens on Termination, which runs none of that workflow code at all.

**Why it breaks Temporal.** Termination is a forceful stop the workflow cannot intercept, observe, or compensate for — a team that only tested graceful Cancel gets a false sense that "compensation is handled" right up until an operator terminates a stuck workflow instead of cancelling it, and the compensation that "always ran in testing" silently doesn't.

**Violating code:**

```java
// Full Saga/compensation logic exists, but nothing external (runbook, doc,
// alerting) accounts for the Terminate path never running any of it.
```

**Recommended fix.** Document explicitly that Terminate bypasses compensation, and prefer Cancel operationally wherever compensation matters; consider a periodic reconciliation Activity as a backstop for the Terminate case.

**Detection strategy.** AST — a documentation-completeness check: flag a workflow with `CanceledFailure`-based compensation logic and no adjacent comment/doc reference to Termination behavior.

**False positive considerations.** N/A — this is an educational flag, not a defect; it should render as guidance in the report, not a build-blocking violation.

**Estimated implementation complexity.** Low — pattern-presence check, no real analysis.

---

## Async APIs, Selectors & Timers

*WG770–WG774 · new category*

> **Why it matters:** Temporal gives you real concurrency primitives inside a single-threaded coroutine model. Used well they turn a workflow into a proper concurrent orchestrator; used carelessly they produce workflows that run three times slower than necessary, or block forever on a condition nothing will ever satisfy.

### WG770 — Independent Activity calls run sequentially instead of in parallel — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** Two or more Activity stub calls are made back-to-back where neither call's arguments depend on the other's result, but neither is wrapped in `Async.function`/`Async.procedure` — they run one after another instead of concurrently.

**Why it breaks Temporal.** Not a correctness bug, but a real, measurable latency tax: every such pair adds the second call's full duration to the workflow's wall-clock time for no reason, and this compounds across a workflow with many independent steps — exactly the shape of a "why does this workflow take four minutes when the actual work is one minute" investigation.

**Violating code:**

```java
Inventory inv = activities.checkInventory(order); // independent
Pricing pr = activities.getPricing(order);         // independent — but runs after inv finishes
```

**Recommended fix.** `Promise<Inventory> inv = Async.function(activities::checkInventory, order); Promise<Pricing> pr = Async.function(activities::getPricing, order); Promise.allOf(inv, pr).get();`

**Detection strategy.** Call Graph + Data Flow — for each pair of adjacent Activity calls, prove neither reads a value produced by the other before deciding they're independent and could have been parallelized.

**False positive considerations.** Medium-high — call ordering is sometimes intentional for reasons the analyzer can't see (rate-limiting a downstream system, a desired audit-log order); should ship as an INFO-level opportunity, not an ERROR.

**Estimated implementation complexity.** Very High — proving independence is real data-flow analysis, and no generic tool has any concept of "these two calls are Temporal-parallelizable."

### WG771 — Workflow.await used without a timeout where an unbounded wait wasn't intended

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** The single-argument `Workflow.await(Supplier<Boolean>)` overload is used to wait on a condition (a signal arriving, an external event) that could plausibly never become true, instead of the two-argument `Workflow.await(Duration, Supplier<Boolean>)` overload.

**Why it breaks Temporal.** An unbounded await on a condition that never fires — because the expected signal was never sent, or a caller made a typo in the workflow ID — leaves the workflow open indefinitely with no automatic recovery path. This is one of the most common "why is this workflow just... stuck" support tickets in any Temporal-backed system.

**Violating code:**

```java
Workflow.await(() -> approved); // waits forever if the approval signal never arrives
```

**Recommended fix.** Race the condition against a timer: `boolean got = Workflow.await(Duration.ofDays(3), () -> approved);` and handle the timeout branch explicitly.

**Detection strategy.** AST — flag the single-argument `Workflow.await` overload wherever the condition references a field only mutated by a signal/update handler (i.e., genuinely externally-driven, not a same-method local condition).

**False positive considerations.** Medium — some unbounded awaits are correct (waiting on the workflow's own internal async work to finish, which is guaranteed to eventually resolve); the rule needs to distinguish externally-driven conditions from internal ones.

**Estimated implementation complexity.** Medium.

### WG772 — Promise.get() called from inside another async callback — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P1 | ERROR |

**Problem.** A blocking `Promise.get()` call appears inside a lambda/callback that is itself passed to another async API (`Promise.thenApply`, an `Async.function` body, a Selector branch) rather than at the workflow's synchronous top level.

**Why it breaks Temporal.** Temporal workflows are cooperatively scheduled on a single logical thread per workflow execution; blocking inside a callback that's itself running as part of that same cooperative scheduler can deadlock the workflow coroutine waiting on a Promise that can only resolve via the very scheduler it's blocking — a deadlock class that's specific to this execution model and doesn't exist in normal Java async code written against a real thread pool.

**Violating code:**

```java
outer.thenApply(v -> {
  return innerPromise.get(); // blocking get() nested inside an async callback
});
```

**Recommended fix.** Compose Promises with `thenCompose`/`Promise.allOf` instead of blocking inside a callback; keep `.get()` calls at the top level of the workflow method.

**Detection strategy.** AST + Call Graph — flag a `Promise.get()` call whose enclosing lexical scope is a lambda argument to another Temporal async API.

**False positive considerations.** Low — this nesting pattern has no legitimate use case in workflow code.

**Estimated implementation complexity.** Medium.

### WG773 — Selector built but never driven in a loop

| Priority | Severity |
|---|---|
| P2 | WARNING |

**Problem.** A `Workflow.newSelector()` is configured with multiple branches meant to repeat (e.g. "keep handling whichever event arrives next"), but `.select()` is called once outside any loop instead of repeatedly.

**Why it breaks Temporal.** A single `.select()` call consumes exactly one ready branch and returns — any further events the Selector was meant to keep servicing are simply never drained, silently dropping work the workflow author clearly intended to handle continuously.

**Violating code:**

```java
Selector sel = Workflow.newSelector();
sel.addFuture(timerPromise, p -> onTimeout());
sel.addFuture(cancelPromise, p -> onCancel());
sel.select(); // fires once; any subsequent event on the same selector is never handled
```

**Recommended fix.** Drive the Selector inside `Workflow.await(() -> sel.hasReady())` or a loop that re-selects until a terminal condition is reached.

**Detection strategy.** AST + CFG — flag a Selector with 2+ branches whose `.select()` call site is not inside a loop construct.

**False positive considerations.** Medium — a genuinely one-shot "first of these two things to happen" race is a legitimate, common pattern and should not be flagged.

**Estimated implementation complexity.** Medium.

### WG774 — Timer left uncancelled after the condition it was racing against resolves

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A `Workflow.newTimer(...)` is started to race against a condition via a Selector, and the condition wins, but the timer's `Promise` is never cancelled.

**Why it breaks Temporal.** An uncancelled timer still fires later and still records a `TimerFired` event, adding a needless entry to history and (if the workflow has since completed) sometimes an unexpected worker wake-up for a workflow nobody is watching anymore.

**Violating code:**

```java
Promise<Void> timeout = Workflow.newTimer(Duration.ofMinutes(30));
Selector.newSelector().addFuture(timeout, t -> {}).addFuture(approved, a -> {}).select();
// if `approved` wins, `timeout` is never cancelled
```

**Recommended fix.** Cancel the losing branch explicitly via its `CancellationScope` once the race resolves.

**Detection strategy.** AST — flag a timer Promise used as a Selector branch with no reachable `.cancel()` call on its scope.

**False positive considerations.** Low, but purely a hygiene/history-size nudge, not a correctness bug — appropriately INFO severity.

**Estimated implementation complexity.** Medium.

---

## Nexus operations

*WG800–WG802 · new category*

> **Why it matters:** Nexus is Temporal's newest cross-namespace, cross-cluster RPC primitive, and it inherits every idempotency and lifecycle problem Activities have — with less tooling and fewer engineers who've internalized the failure modes yet. This is the category almost no other static analysis tool will touch for a long while.

### WG800 — Nexus operation handler ignores the request token for deduplication — ★★ FLAGSHIP DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** A Nexus operation's `start` implementation performs a real side effect (a charge, a write) without checking the operation's request identifier against previously-seen requests.

**Why it breaks Temporal.** A Nexus caller can legitimately retry a `StartOperation` call after a network failure, the same way an Activity caller retries — but Nexus crosses namespace and often cluster boundaries, where the caller has even less visibility into whether the first attempt actually landed. A handler that doesn't dedupe on the request token will double-execute the side effect, and because this is brand-new surface area, almost nobody has internalized this failure mode the way the field has for Activities.

**Violating code:**

```java
public OperationStartResult<Void> start(OperationContext ctx, OperationStartDetails d, Charge c) {
  paymentGateway.charge(c); // no check against ctx.getRequestId()
  return OperationStartResult.sync(null);
}
```

**Recommended fix.** Track applied request IDs (the same idempotency-key discipline as Activities) and short-circuit a repeat with the original result rather than re-executing.

**Detection strategy.** Symbol Resolution + Data Flow — flag a Nexus operation handler with a call matching a mutating-verb heuristic and no reachable read of `OperationContext`'s request identifier feeding a short-circuit branch.

**False positive considerations.** Medium — depends on the same imperfect "looks like a side effect" heuristic as WG750; still valuable as a strong nudge given how new and under-taught this API is.

**Estimated implementation complexity.** Very High — new API surface, thin prior art to build detection heuristics from.

### WG801 — Async Nexus operation started with no completion/cancellation handling — ★ DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A workflow starts an async Nexus operation and never awaits its handle or wires up cancellation propagation to it.

**Why it breaks Temporal.** The same fire-and-forget failure shape as WG700's unclosed child workflow, now for a call that may be reaching an entirely different namespace or cluster — an abandoned Nexus operation is materially harder to discover and clean up after the fact than a local child workflow, since it isn't even in the same namespace's visibility list.

**Violating code:**

```java
NexusOperationHandle<Result> h = Workflow.startNexusOperation(service, op, input);
// handle dropped; workflow proceeds without awaiting or cancelling it
```

**Recommended fix.** Await the handle's result, or explicitly cancel it as part of the workflow's own cancellation handling, matching the discipline already expected of child workflows.

**Detection strategy.** Call Graph — flag a Nexus operation handle never referenced again after the call that produced it.

**False positive considerations.** Low-medium.

**Estimated implementation complexity.** High.

### WG802 — Nexus operation contract typed as raw JSON/Object instead of a schema

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A Nexus service's operation input/output type is a loosely-typed `Map`/raw JSON payload rather than a concrete, versioned type.

**Why it breaks Temporal.** Nexus's entire value proposition over ad hoc cross-team RPC is a stable, typed contract other teams can build against without reading your source — a loosely-typed contract undermines exactly the property that makes Nexus worth adopting over a plain HTTP call in the first place.

**Violating code:**

```java
public interface RefundService {
  OperationHandler<Map<String, Object>, Map<String, Object>> refund(); // no real schema
}
```

**Recommended fix.** Define concrete request/response record types for every Nexus operation, the same way Activity input/output types are expected to be concrete.

**Detection strategy.** Symbol Resolution — check Nexus operation handler type parameters against a denylist of loosely-typed containers.

**False positive considerations.** Low.

**Estimated implementation complexity.** Low.

---

## Scheduling & Cron

*WG820–WG822 · new category*

> **Why it matters:** A cron workflow runs forever by definition, which makes every history-growth and overlap-handling mistake in this catalog compound indefinitely instead of ending when the workflow completes.

### WG820 — Legacy cronSchedule string used with no documented migration decision

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** `WorkflowOptions.setCronSchedule(...)` is used for a new workflow with no note on why the newer `ScheduleClient`/Schedule API (which offers overlap policy, pause/backfill, and richer visibility) wasn't chosen instead.

**Why it breaks Temporal.** Not incorrect, but the cron-string API has materially fewer operational controls (no explicit overlap policy, weaker pause/backfill/visibility story) than the dedicated Schedule API — a team that doesn't know the newer API exists ends up under-equipped to handle overlap and backfill scenarios that come up eventually for any long-lived cron job.

**Violating code:**

```java
WorkflowOptions.newBuilder().setCronSchedule("0 * * * *").build(); // legacy API, no ramp story
```

**Recommended fix.** Prefer `ScheduleClient.createSchedule(...)` for new recurring workflows; migrate existing cron workflows deliberately, not by default.

**Detection strategy.** AST — flag any `setCronSchedule` call.

**False positive considerations.** Low, but advisory-only — cron strings remain fully supported and are sometimes the simpler right choice.

**Estimated implementation complexity.** Low.

### WG821 — State carried between cron runs grows without bound

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A cron workflow's result is fed back in as the next run's input (a common pattern for carrying forward accumulated state) and that state grows — an ever-lengthening list, a growing map — with no pruning between runs.

**Why it breaks Temporal.** Every run's input is a payload subject to the same size limits as any other workflow input; a cron job that never prunes its carried-forward state will eventually hit a payload-size wall purely as a function of elapsed calendar time, regardless of how efficient any single run is.

**Violating code:**

```java
@Override public CronState run(CronState previous) {
  previous.processedIds.addAll(newIdsThisRun()); // never trimmed, carried forward forever
  return previous;
}
```

**Recommended fix.** Prune/summarize carried-forward state every run (keep only a bounded recent window, or move historical detail to external storage referenced by ID).

**Detection strategy.** Data Flow — trace the cron workflow's return value back into its own next-run input type and check for an unbounded collection field only ever appended to.

**False positive considerations.** Medium.

**Estimated implementation complexity.** High.

### WG822 — Schedule created with no explicit overlap policy

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A `Schedule` is created via the Schedule API with `ScheduleOverlapPolicy` left at its default, for a workflow whose individual runs can occasionally exceed the schedule's own interval.

**Why it breaks Temporal.** Whether an overrunning run should skip, buffer, or run concurrently with the next scheduled trigger is a real behavioral decision — leaving it at the default means the author never consciously made that choice, and may be surprised by which one they got.

**Violating code:**

```java
Schedule.newBuilder().setAction(action).setSpec(spec).build(); // overlap policy never set
```

**Recommended fix.** Set `ScheduleOverlapPolicy` explicitly ( `SKIP`, `BUFFER_ONE`, `ALLOW_ALL`, etc.) based on the workflow's actual runtime characteristics.

**Detection strategy.** AST — check whether `setPolicy`/overlap policy was set on the Schedule builder.

**False positive considerations.** Low, but this is an out-of-workflow-source config check (schedule creation often lives in an admin script or CLI call, not the workflow definition itself) — scope needs to include wherever `ScheduleClient` is actually called.

**Estimated implementation complexity.** Low.

---

## Performance & history optimization

*WG400–WG405 · existing category*

> **Why it matters:** Temporal's event history is the source of truth for every workflow, and it has real size and event-count ceilings. Every rule here maps to the same incident shape: a workflow that worked fine for months and then hits a wall as volume grows.

### WG400 — Long-lived loop with no ContinueAsNew as history approaches practical limits

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** A workflow's main loop (an "entity"/"actor" style workflow processing an unbounded stream of signals or timers) has no `Workflow.continueAsNew` call gated on event count or history size.

**Why it breaks Temporal.** This is the single most common "history too large" production incident: a workflow that behaves perfectly for its first several thousand events, then starts degrading in replay time, and eventually hits a hard size/event-count ceiling, at which point it can no longer make progress at all.

**Violating code:**

```java
while (true) {
  Workflow.await(() -> hasNewEvent());
  process(nextEvent()); // runs forever, history grows forever, no ContinueAsNew anywhere
}
```

**Recommended fix.** Track event/history-size and call `Workflow.continueAsNew(currentState)` once a threshold is crossed, carrying forward only the state actually needed to resume.

**Detection strategy.** Call Graph + CFG — flag an unbounded `while (true)`/recursive loop rooted at a workflow entry point with no reachable `Workflow.continueAsNew` call anywhere in the loop body.

**False positive considerations.** Low-medium — some long-running loops are genuinely bounded by external termination conditions the analyzer can verify (a fixed max iteration count); should recognize those.

**Estimated implementation complexity.** Medium — flagship rule, reasonably tractable with existing call-graph infrastructure.

### WG401 — Large collection passed directly as workflow or Activity payload

| Priority | Severity |
|---|---|
| P0 | WARNING |

**Problem.** A workflow or Activity method signature accepts/returns a raw collection or blob with no size discipline, where the collection can plausibly grow large (e.g. "all line items," "all events").

**Why it breaks Temporal.** Every payload flows through the `DataConverter` and the gRPC transport, both with practical size ceilings (the default gRPC message limit is a few MB); a workflow that works in every test with ten items and fails in production with ten thousand is a common, avoidable class of incident.

**Violating code:**

```java
@WorkflowMethod void processOrder(List<LineItem> allItems); // unbounded, no claim-check
```

**Recommended fix.** Use a "claim check" pattern — store the large payload externally (blob storage, a database row) and pass a reference/ID through the workflow instead.

**Detection strategy.** Symbol Resolution — flag workflow/Activity method parameters typed as unbounded collections with no accompanying size-limit contract (e.g. a paginated type instead of a raw `List`).

**False positive considerations.** High — most collections are genuinely small; this needs a size-heuristic or org-configurable threshold rather than flagging every `List` parameter.

**Estimated implementation complexity.** Medium.

### WG402 — Large or high-churn value stored in Memo or a Search Attribute

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A JSON blob, large string, or a field updated on every workflow task is written into `Memo` or a `SearchAttribute`.

**Why it breaks Temporal.** Search Attributes are meant for small, typed, queryable tags and get indexed (often into Elasticsearch); Memo is meant for small descriptive metadata. Both have real size limits and real re-indexing cost — using either as general-purpose storage is a common misuse that degrades visibility store performance for the whole namespace, not just the offending workflow.

**Violating code:**

```java
Workflow.upsertSearchAttributes(Map.of("orderPayload", fullOrderJson)); // large blob as a tag
```

**Recommended fix.** Keep Search Attributes to small, typed, genuinely queryable fields (status, tenant, region); keep large/derived data out of both Memo and Search Attributes entirely.

**Detection strategy.** Data Flow — trace the value passed to `upsertSearchAttributes`/the Memo builder back to its source and flag an obviously large/composite value (a full object serialization) rather than a scalar.

**False positive considerations.** Medium.

**Estimated implementation complexity.** Medium.

### WG403 — High-frequency Activity call with an uncapped retry policy inside a loop

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** Same shape as WG106, presented here from the history-growth angle: an Activity called per-iteration of a large loop, with no retry ceiling, multiplies WG101's single-call history impact by the loop's iteration count.

**Why it breaks Temporal.** A failure storm hitting an uncapped-retry Activity called thousands of times can, on its own, push a workflow over practical history-size limits faster than almost any other single misconfiguration in this catalog.

**Violating code:**

```java
for (Item i : thousands) { activities.enrich(i); } // uncapped retry, per-item
```

**Recommended fix.** Cap retries per call and/or batch the loop into a single Activity invocation over the whole collection.

**Detection strategy.** Call Graph + CFG — identical engine to WG106, kept as a distinct rule id here so it surfaces under the Performance category's reporting lens as well as Activities'.

**False positive considerations.** Medium.

**Estimated implementation complexity.** High.

### WG404 — CPU-bound work executed directly on the workflow thread

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** Workflow code performs a non-trivial computation directly — sorting/aggregating a large collection, a heavy regex, hashing a large payload — instead of delegating it to an Activity.

**Why it breaks Temporal.** A workflow task shares a worker's sticky cache and thread pool with every other workflow task on that worker; a slow workflow task blocks the worker's ability to make progress on other workflows, and — because replay re-executes workflow code — that same expensive computation is paid again every time history needs to be reconstructed, multiplying its real cost.

**Violating code:**

```java
List<Result> sorted = bigList.stream().sorted(expensiveComparator).toList(); // in workflow code
```

**Recommended fix.** Move CPU-bound work into an Activity, which runs on a worker's normal (non-sticky, non-replayed) execution path.

**Detection strategy.** AST heuristic — flag workflow-code operations over collections above a configurable size threshold, or calls to known expensive standard-library operations (regex compilation, cryptographic hashing) inside workflow methods.

**False positive considerations.** High — "expensive" is inherently fuzzy without runtime profiling data; best shipped as an INFO-level nudge with a tunable threshold.

**Estimated implementation complexity.** Medium.

### WG405 — Query-serving state kept far larger than any query actually needs

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A workflow retains a large amount of detailed in-memory state purely so its Query handlers can answer with full fidelity, when a smaller derived summary would satisfy every actual caller.

**Why it breaks Temporal.** Larger workflow state means a larger memory footprint per sticky-cached execution and — combined with WG401/WG402 — more surface area for hitting payload limits; it's a softer, architecture-level cousin of the harder history-size rules above.

**Violating code:**

```java
private final List<FullEventRecord> allEvents = new ArrayList<>(); // kept only for one summary query
```

**Recommended fix.** Maintain a derived summary alongside (or instead of) full detail once it's clear no query needs the raw records.

**Detection strategy.** Data Flow — cross-reference a large state field against every Query handler that actually reads it, and how much of it each one uses.

**False positive considerations.** High — genuinely needs the full detail in plenty of real cases; advisory-only.

**Estimated implementation complexity.** High.

---

## Observability

*WG850–WG853 · new category*

> **Why it matters:** A workflow that logs so much replay noise nobody can find the signal, or fails silently with no fleet-wide way to find it, is a workflow nobody can operate. Temporal ships purpose-built tools for both problems, and most codebases use neither.

### WG850 — Plain SLF4J logger used in workflow code instead of Workflow.getLogger() — ★★ FLAGSHIP DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | WARNING |

**Problem.** Workflow code obtains its logger via `LoggerFactory.getLogger(...)` directly rather than `Workflow.getLogger(...)`.

**Why it breaks Temporal.** This is a genuinely Temporal-specific SDK feature almost nobody outside the ecosystem would know to check for: `Workflow.getLogger` automatically suppresses duplicate log lines during replay and tags every entry with workflow/run-ID context. A plain logger logs identically on every replay, meaning the same log line can appear dozens of times for a single logical event, drowning real signal in replay noise and making log-based incident response actively worse than having no logs from that workflow at all.

**Violating code:**

```java
private static final Logger log = LoggerFactory.getLogger(OrderWorkflowImpl.class); // logs on every replay
```

**Recommended fix.** `private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);` — replay-aware deduping and context tagging, with no other code change needed.

**Detection strategy.** AST — flag any `LoggerFactory.getLogger(...)` call inside a class implementing a `@WorkflowInterface`-annotated interface.

**False positive considerations.** Very low — there is no reason to prefer the plain logger inside workflow code specifically.

**Estimated implementation complexity.** Low — a single, unambiguous AST pattern with outsized real-world value. Ship this early.

### WG851 — No Search Attributes set for fleet-wide operational visibility

| Priority | Severity |
|---|---|
| P1 | INFO |

**Problem.** A workflow type sets no `SearchAttributes` at all, leaving on-call with no way to query "every stuck workflow for customer X" or "every failed run of this type today" without scanning execution history one at a time.

**Why it breaks Temporal.** At any real fleet scale, Search Attributes are the only practical way to find a specific workflow or class of workflows across a namespace; a workflow type with none is effectively invisible to fleet-wide incident response.

**Violating code:**

```java
WorkflowOptions.newBuilder().setWorkflowId(id).build(); // no typed/searchable tags at all
```

**Recommended fix.** Set at least the org's minimum operational tag set (see WG903) — tenant/customer ID, environment, and a status field kept current via `upsertSearchAttributes`.

**Detection strategy.** AST — check for at least one `upsertSearchAttributes` call reachable from the workflow's entry point.

**False positive considerations.** Low, but value is highly org-dependent — pairs naturally with a configurable Org Policy rule (WG903) rather than a fixed list.

**Estimated implementation complexity.** Low.

### WG852 — No business correlation ID propagated into Activity context

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** Activity calls carry no shared correlation/tracing identifier that would let an engineer join workflow-side logs to Activity-worker-side logs for the same logical operation.

**Why it breaks Temporal.** Activities frequently run on entirely separate worker processes/fleets from the workflow itself; without a shared correlation ID, reconstructing "what happened for this one order" during an incident means manually cross-referencing timestamps instead of a single traceable ID.

**Violating code:**

```java
activities.chargeCard(card, amount); // no correlation id threaded through at all
```

**Recommended fix.** Thread a stable correlation ID (workflow ID, or a dedicated business ID) through every Activity call's arguments and into that Activity's own logging context.

**Detection strategy.** Symbol Resolution — heuristic, checking whether any Activity interface's method signatures include a recognizable ID-shaped parameter; inherently approximate.

**False positive considerations.** High — hard to verify precisely from source alone; best kept as a soft, advisory INFO rule.

**Estimated implementation complexity.** Medium.

### WG853 — Retried workflow task with no attempt-count-aware logging

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** Workflow or Activity code never reads `Workflow.getInfo().getAttempt()` / `Activity.getExecutionContext().getInfo().getAttempt()`, so repeated retries of the same failing step are indistinguishable in logs from a single clean run.

**Why it breaks Temporal.** Without attempt-number context, diagnosing "why did this take five retries" during an incident requires reconstructing retry history manually from timestamps rather than reading it directly off each log line.

**Violating code:**

```java
log.info("charging card"); // identical on attempt 1 and attempt 12
```

**Recommended fix.** Include the current attempt number in log context for any Activity/workflow step with a non-trivial retry policy.

**Detection strategy.** AST — advisory pattern check, low precision.

**False positive considerations.** High — genuinely optional in most cases; ship as an INFO-only nudge, not something that should ever block a build.

**Estimated implementation complexity.** Low.

---

## Security

*WG600–WG603 · existing category*

> **Why it matters:** Temporal's event history is durable, often long-retained, and visible to anyone with namespace read access — sometimes indexed into Elasticsearch for Search Attributes. That makes "what goes into a workflow input, signal, or Memo" a materially different security question than "what goes into a log line," and most engineers reason about it as if it were the latter.

### WG600 — Sensitive data passed as workflow input, signal, or Memo — ★★ FLAGSHIP DIFFERENTIATOR

| Priority | Severity |
|---|---|
| P0 | ERROR |

**Problem.** Recognizably sensitive data — a credential-shaped string, an SSN/card-number-shaped value, a field literally named `password`/`ssn`/`cardNumber` — flows into a workflow's input arguments, a Signal/Update payload, or a Memo field.

**Why it breaks Temporal.** Unlike an application log line that rotates and expires, workflow Event History is durable by design — it's the mechanism replay depends on — and is visible to anyone with read access to the namespace via the Web UI, CLI, or API, indefinitely (or per the namespace's retention policy, which is often long). Treating a workflow input like a normal method argument, when it's actually more like writing a permanent, broadly-readable audit record, is the single highest-value security rule this catalog can offer, precisely because it's invisible unless you already know how Temporal persists history.

**Violating code:**

```java
@WorkflowMethod void processPayment(String cardNumber, String cvv); // now permanently in history
```

**Recommended fix.** Never pass raw sensitive values into workflow-visible APIs; pass a reference/token (a vault key, a tokenized payment reference) and resolve the real value only inside an Activity, on the worker, at the moment it's needed — or apply a Payload Codec (see WG601) if the sensitive value must be workflow-visible.

**Detection strategy.** AST + Symbol Resolution — flag workflow-visible method parameters (workflow entry points, `@SignalMethod`, `@UpdateMethod`) and Memo/SearchAttribute values whose name or declared type matches a configurable sensitive-data pattern list.

**False positive considerations.** Medium — name-based heuristics ("cardNumber") will miss disguised fields and occasionally flag genuinely non-sensitive fields with a similar name; should be configurable per organization's own sensitive-field taxonomy.

**Estimated implementation complexity.** Medium to build a first, name-heuristic version; the value-to-effort ratio here is the best in the whole catalog.

### WG601 — Known-sensitive workflow uses the default DataConverter with no encryption codec

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A workflow type already flagged (or self-declared, e.g. via a marker annotation) as handling sensitive data runs under the default `DataConverter`, with no Payload Codec providing encryption-at-rest for its history.

**Why it breaks Temporal.** Where sensitive data genuinely must be workflow-visible, a Payload Codec is the SDK-provided mechanism to keep it encrypted at rest in history and in the Web UI, decrypted only by clients holding the key — skipping it means every such workflow's history is one namespace-read-access grant away from exposing the sensitive value in plaintext.

**Violating code:**

```java
WorkflowClientOptions.newBuilder().build(); // default converter, no codec, for a PII-handling workflow
```

**Recommended fix.** Configure a `Payload Codec` (via `CodecDataConverter`) for any workflow type known to carry sensitive data.

**Detection strategy.** Config Analysis — checks client/worker configuration rather than workflow source; cross-references workflow types flagged by WG600.

**False positive considerations.** Low once paired with WG600's flagging, but requires WoGu to reason about worker/client bootstrap code, not just workflow implementations — a scope expansion.

**Estimated implementation complexity.** High.

### WG602 — Signal/Update handler trusts caller input with no validation

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A `@SignalMethod`/`@UpdateMethod` applies its payload directly to workflow state with no bounds/range/shape validation.

**Why it breaks Temporal.** Any client with namespace access and a workflow ID can send it a signal — a handler is genuinely an external attack surface, the same as a public API endpoint, not trusted internal code. Authorization for *who* may call `signalWorkflow` is typically enforced at the transport layer (mTLS, namespace ACLs), but payload validation is still entirely the workflow author's job, and it's easy to forget precisely because signal handlers read like internal method calls, not API boundaries.

**Violating code:**

```java
@SignalMethod public void setDiscount(double pct) {
  this.discountPct = pct; // no bounds check — a caller can send -500 or 1e300
}
```

**Recommended fix.** Validate every signal/update payload the same way a public API handler would — range/shape checks, and rejection (via an `@UpdateValidator` for Updates) rather than silent acceptance.

**Detection strategy.** AST + Data Flow — flag a signal/update parameter applied directly to a field with no reachable conditional/guard referencing that parameter beforehand.

**False positive considerations.** High — plenty of legitimately unbounded fields exist; best as an INFO-level nudge unless the field type is something with obvious natural bounds (a percentage, a quantity).

**Estimated implementation complexity.** Medium.

### WG603 — Hardcoded credential passed as an Activity argument instead of resolved on the worker

| Priority | Severity |
|---|---|
| P1 | ERROR |

**Problem.** A literal API key/credential string is embedded in workflow or Activity source, or passed as a plain Activity argument, rather than resolved from a secrets manager inside the Activity implementation at execution time.

**Why it breaks Temporal.** This is a variant of a generic "no hardcoded secrets" rule, but with a Temporal-specific twist: an Activity argument — unlike a value read fresh inside the Activity body — flows through the same durable, potentially-indexed history path as any other payload (see WG600), so a secret passed this way is doubly exposed: once in source control, once in Event History.

**Violating code:**

```java
activities.callExternalApi("sk_live_9f2c..."); // secret both in source and now in workflow history
```

**Recommended fix.** Resolve credentials from a secrets manager inside the Activity implementation itself, keyed by a non-sensitive reference passed through the workflow if any reference is needed at all.

**Detection strategy.** AST — pattern-match string literals against common credential shapes (API key prefixes, JWT structure) passed as Activity call arguments.

**False positive considerations.** Low — literal credential-shaped strings in source are essentially never intentional.

**Estimated implementation complexity.** Low.

---

## Enterprise / org policy

*WG900–WG904 · existing category*

> **Why it matters:** At fleet scale, the question stops being "is this workflow correct" and becomes "does this workflow follow the conventions the rest of the org depends on to operate it." This is the category that turns WoGu from a linter into a platform, because these rules are configured by the org, not shipped by WoGu.

### WG900 — Workflow ID doesn't match the org's naming convention

| Priority | Severity |
|---|---|
| P2 | WARNING |

**Problem.** A workflow is started with a `workflowId` that doesn't match an org-mandated pattern (e.g. `{tenant}-{entity}-{uuid}`), configured per-organization rather than hardcoded by WoGu.

**Why it breaks Temporal.** Workflow ID is the primary handle every operational tool (dedup logic, the CLI, dashboards, runbooks) keys off; an inconsistent naming scheme across an org's workflow fleet makes cross-cutting tooling and search meaningfully harder to build and use reliably.

**Violating code:**

```java
WorkflowOptions.newBuilder().setWorkflowId("wf-" + System.currentTimeMillis()).build(); // no tenant/entity prefix
```

**Recommended fix.** Adopt the org's documented Workflow ID convention; enforce it centrally rather than per-team.

**Detection strategy.** AST + Data Flow — check the string(s) feeding `setWorkflowId` against a configurable regex/policy.

**False positive considerations.** Low once configured, but entirely meaningless without org-specific configuration — this rule is a policy *engine*, not a fixed check.

**Estimated implementation complexity.** Medium — the check itself is simple; the differentiator is the configuration surface around it.

### WG901 — Activity/Retry options built ad hoc instead of from the org's shared template

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** An `ActivityOptions`/`RetryOptions` is built inline at the call site rather than sourced from a shared, org-mandated default constant.

**Why it breaks Temporal.** Ad hoc options mean WG100/WG101-class misconfigurations get reintroduced independently at every call site instead of being fixed once, centrally, for the whole org — this rule is less about any single call site being wrong and more about preventing the same mistake from being made a hundred different times across a large codebase.

**Violating code:**

```java
ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(); // one-off, not the shared template
```

**Recommended fix.** Reference a shared `DEFAULT_ACTIVITY_OPTIONS`-style constant (or a small, org-defined family of them) instead of building options inline per call site.

**Detection strategy.** Symbol Resolution — check whether the builder chain feeding a stub call resolves back to an org-designated shared constant versus an inline construction.

**False positive considerations.** Medium — some Activities genuinely need bespoke options; the policy should allow documented per-call overrides.

**Estimated implementation complexity.** Medium.

### WG902 — Disallowed or default task queue used in a multi-tenant worker fleet

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A workflow or Activity is registered against the default/global task queue rather than a team- or tenant-scoped one, in an org that runs multiple teams' workers against a shared namespace.

**Why it breaks Temporal.** Task queue is Temporal's isolation and scaling boundary between independently-owned worker fleets; a workflow landing on the wrong (or a shared default) queue can starve or be starved by unrelated teams' workloads, and complicates capacity planning that assumes queue-level isolation.

**Violating code:**

```java
WorkflowOptions.newBuilder().setTaskQueue("default").build(); // shared queue in a multi-tenant fleet
```

**Recommended fix.** Use a team/tenant-scoped task queue per the org's worker-fleet isolation policy.

**Detection strategy.** AST — check the string passed to `setTaskQueue` against an org-configured denylist/allowlist.

**False positive considerations.** Low once configured; meaningless without it, same as WG900.

**Estimated implementation complexity.** Low.

### WG903 — Missing org-required Search Attributes (owning team, environment, tier)

| Priority | Severity |
|---|---|
| P1 | WARNING |

**Problem.** A workflow doesn't set one or more Search Attributes an org has declared mandatory for every workflow type (e.g. `Team`, `Environment`) — the configuration-driven completion of WG851.

**Why it breaks Temporal.** Without these, on-call at 2am can't answer "who owns this workflow" or "is this a prod or staging execution" from the Web UI alone — exactly the fleet-operability gap WG851 identifies, made concrete and enforceable once an org has actually decided what the required tag set is.

**Violating code:**

```java
Workflow.upsertSearchAttributes(Map.of("orderId", id)); // missing org-required "Team"/"Environment"
```

**Recommended fix.** Set every Search Attribute the org's policy config declares mandatory, ideally via a shared helper every workflow type calls at startup.

**Detection strategy.** AST + Data Flow — compare the set of keys passed to every `upsertSearchAttributes` call in a workflow against an org-configured required-key list.

**False positive considerations.** Low once configured — this is precisely what a policy-engine rule should be: unambiguous once the org has stated its own requirement.

**Estimated implementation complexity.** Medium.

### WG904 — New workflow/signal/query/update type shipped with no rule-doc-equivalent runbook link

| Priority | Severity |
|---|---|
| P2 | INFO |

**Problem.** A new public workflow entry point, Signal, Query, or Update is added with no adjacent documentation reference (a runbook URL, an internal wiki link) an on-call engineer could follow during an incident.

**Why it breaks Temporal.** Every externally-callable surface on a workflow is effectively a small API a future on-call engineer, who didn't write it, will have to operate under pressure — the same reasoning that makes WoGu itself require a documentation page per rule applies just as directly to the workflow catalog an org builds with WoGu's help.

**Violating code:**

```java
@SignalMethod public void forceRetry() { ... } // no runbook link anywhere near it
```

**Recommended fix.** Require a documentation-reference annotation or adjacent comment/link for every new externally-callable workflow surface, enforced the same way a PR template enforces a changelog entry.

**Detection strategy.** AST — presence check for a doc-reference annotation/comment near each `@SignalMethod`/`@QueryMethod`/ `@UpdateMethod`/`@WorkflowMethod`.

**False positive considerations.** Low, but entirely a process rule, not a technical one — best positioned as an opt-in org policy, not a default.

**Estimated implementation complexity.** Low.

---

## What no other tool will find

Sixteen rules above are marked ★ differentiators — the ones a generic Java linter, a generic call-graph tool, or even a generic "cloud workflow" linter has essentially no path to ever implementing, because the violation isn't wrong in Java. It's only wrong once you know what a specific Temporal guarantee actually promises.

Three of them deserve special attention as the strongest possible proof points for WoGu as a category, not just a tool:

- **WG300** A workflow completing while a signal handler is still in flight is a bug class that requires understanding the relationship between `@SignalMethod` lifecycle and `Workflow.await`-gated completion — a Temporal concept with no analog in generic concurrent-programming analysis.
- **WG850** Recommending `Workflow.getLogger()` over a plain SLF4J logger requires knowing that Temporal replays workflow code and specifically de-duplicates log output for it — an SDK implementation detail, not a coding convention any style guide would independently arrive at.
- **WG600** Treating a workflow input differently from a log line because Event History is durable, broadly readable, and often indexed is a security model inversion unique to durable-execution systems — a generic secret-scanner has no concept of "this string will live forever in a place more people can read than your logs."

The practical implication for the roadmap: WoGu's moat isn't better AST parsing or a faster call graph — every static analysis vendor can build those. It's the accumulated, hard-won knowledge of exactly where the Temporal execution model diverges from what looks like ordinary Java, encoded as rules. That knowledge compounds with every SDK feature the catalog above hasn't covered yet (Update-with-Start, Interceptors, richer Worker Versioning), and it's genuinely difficult for a team without deep Temporal operating experience to replicate, which is exactly what makes it defensible.

## Taxonomy note

`RuleCategory` today reserves `WG700–WG899` as unassigned headroom. This catalog proposes using it for six new categories — Child Workflows, Queries, Cancellation, Async/Selectors/Timers, Nexus, and Scheduling — rather than overloading existing categories that don't quite fit (Queries, for instance, are adjacent to but distinct from Signals). Adding enum constants to a public API is compatible under this project's own `MINOR`-bump policy in practice, but is worth flagging explicitly: any consumer code with an exhaustive `switch` over `RuleCategory` would need a default arm added first. Worth a one-line note in the next release's changelog either way.

---

_Prepared as a brainstorm for WoGu's rule roadmap. Not yet implemented — every complexity estimate and false-positive assessment above is a starting judgment call for scoping, not a committed spec._
