# Call Graph Analysis

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

---

[← Back to README](../README.md)
