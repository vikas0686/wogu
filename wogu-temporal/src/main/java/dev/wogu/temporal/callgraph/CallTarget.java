package dev.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

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

  /**
   * Whether {@code creation} (found in {@code unit}) matches this target, for targets
   * that flag a constructor (e.g. {@code new java.util.Random()}) rather than a method
   * call. Defaults to never matching, so existing method-call-only targets (like
   * {@link StaticMethodCallTarget}) need no change to keep working exactly as before.
   *
   * @param creation the constructor call expression being examined
   * @param unit the compilation unit {@code creation} was found in, for import/context checks
   */
  default boolean matchesConstructor(ObjectCreationExpr creation, CompilationUnit unit) {
    return false;
  }

  /**
   * Human-readable rendering of a matching constructor call, e.g.
   * {@code "new Random()"}. Only called when {@link #matchesConstructor} returned
   * {@code true}, so the default (which never matches) never needs to implement this.
   */
  default String describeConstructor(ObjectCreationExpr creation) {
    throw new UnsupportedOperationException(getClass().getName() + " does not match constructors");
  }
}
