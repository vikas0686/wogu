package dev.wogu.temporal.callgraph;

import dev.wogu.api.CallPathFrame;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One call matching a {@link CallTarget}, found by {@link CallGraphAnalyzer}: the full
 * path from the traversal's entry point down to the match, plus the identity of the class
 * whose method directly contains the matching call (the frame with the flagged
 * expression's own display text does not itself carry that information).
 *
 * @param path frames from the entry point down to the flagged call, inclusive
 * @param containingClassName simple name of the class whose method body the match was
 *     found in
 * @param file source file the match was found in
 * @param line 1-based line number of the matching call
 * @param executionContext the {@link ExecutionContext} the match was found in — e.g.
 *     {@link ExecutionContext#SIDE_EFFECT} if it is reachable from a
 *     {@code Workflow.sideEffect(...)} callback, so a rule can be suppressed there without
 *     needing its own AST-walking logic
 */
public record CallGraphMatch(
    List<CallPathFrame> path, String containingClassName, Path file, int line, ExecutionContext executionContext) {

  public CallGraphMatch {
    path = List.copyOf(Objects.requireNonNull(path, "path"));
    Objects.requireNonNull(containingClassName, "containingClassName");
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(executionContext, "executionContext");
    if (path.isEmpty()) {
      throw new IllegalArgumentException("path must not be empty");
    }
  }
}
