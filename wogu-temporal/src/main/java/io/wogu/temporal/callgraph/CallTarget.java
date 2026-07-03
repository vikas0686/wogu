package io.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;

/**
 * A pattern a {@link CallGraphAnalyzer} traversal looks for at every call site it visits,
 * e.g. "this is a call to {@code java.util.UUID.randomUUID()}".
 *
 * <p>This is the seam that lets one call graph engine serve many rules: a rule for
 * {@code Thread.sleep()}, {@code Instant.now()}, or {@code Math.random()} is just a
 * different {@code CallTarget} implementation reused against the same traversal logic,
 * rather than a new scanner.
 */
public interface CallTarget {

  /**
   * Whether {@code call} (found in {@code unit}) matches this target.
   *
   * @param call the call expression being examined
   * @param unit the compilation unit {@code call} was found in, for import/context checks
   */
  boolean matches(MethodCallExpr call, CompilationUnit unit);

  /**
   * Human-readable rendering of a matching call, used as the final frame in a call path,
   * e.g. {@code "UUID.randomUUID()"}.
   */
  String describe(MethodCallExpr call);
}
