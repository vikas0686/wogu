package io.wogu.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One hop in the execution path from a workflow entry point down to the location that
 * triggered a {@link Violation}.
 *
 * <p>A rule that only inspects a single method (no cross-method traversal) still produces
 * a path with at least one frame: the location of the flagged code itself. A rule built on
 * a call-graph analysis (see {@code wogu-temporal}'s {@code CallGraphAnalyzer}) produces
 * one frame per method boundary crossed, so a report can render the full chain, e.g.:
 *
 * <pre>
 * PaymentWorkflowImpl.processPayment()
 *   -&gt; OrderService.createOrder()
 *   -&gt; CustomerService.generateId()
 *   -&gt; UUID.randomUUID()
 * </pre>
 *
 * @param displayName human-readable location, e.g. {@code "OrderService.createOrder()"}
 *     or, for the final frame, the flagged expression itself, e.g. {@code "UUID.randomUUID()"}
 * @param file source file this frame is located in
 * @param line 1-based source line number
 */
public record CallPathFrame(String displayName, Path file, int line) {

  public CallPathFrame {
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(file, "file");
    if (line < 1) {
      throw new IllegalArgumentException("line must be >= 1, was " + line);
    }
  }

  @Override
  public String toString() {
    return "%s (%s:%d)".formatted(displayName, file, line);
  }
}
