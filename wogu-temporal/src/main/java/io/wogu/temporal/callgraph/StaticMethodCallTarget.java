package io.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

/**
 * A reusable {@link CallTarget} matching calls to one specific method, identified by the
 * fully qualified name of the class it is declared on and its method name — e.g.
 * {@code new StaticMethodCallTarget("java.util.UUID", "randomUUID")}.
 *
 * <p>This is the shared detection primitive behind every "flag this specific method call"
 * rule (WG001's {@code UUID.randomUUID()}, WG002's {@code Thread.sleep()}, WG003's
 * non-deterministic time APIs, WG004-WG010's determinism rules, and any future rule of
 * the same shape): a rule author only supplies the class and method name, not new
 * AST-matching logic. Despite the name, it matches both static calls (its primary,
 * syntactic detection path) and instance calls resolvable to the given declaring type
 * (its resolution-based fallback, needed for calls like {@code randomInstance.nextInt()}
 * where the class name never appears at the call site).
 *
 * <p>A call matches when it is written as:
 *
 * <ul>
 *   <li>the simple class name as scope (e.g. {@code UUID.randomUUID()}), with either an
 *       explicit import of the qualified name, a wildcard import of its package, or —
 *       for classes in {@code java.lang}, which need no import — no conflicting import of
 *       a different class with the same simple name;
 *   <li>the fully qualified name written inline as scope (e.g.
 *       {@code java.util.UUID.randomUUID()});
 *   <li>no scope at all, when the method is statically imported (e.g.
 *       {@code import static java.util.UUID.randomUUID;}); or
 *   <li>an instance call (any scope expression, e.g. a variable) that resolves to a
 *       method of this name declared on this class, for calls where the class name isn't
 *       written at the call site at all.
 * </ul>
 */
public final class StaticMethodCallTarget implements CallTarget {

  private final String methodName;
  private final QualifiedClassNameMatcher classNameMatcher;

  /**
   * @param qualifiedClassName fully qualified name of the class the method is declared
   *     on, e.g. {@code "java.lang.Thread"}
   * @param methodName the method's name, e.g. {@code "sleep"}
   */
  public StaticMethodCallTarget(String qualifiedClassName, String methodName) {
    this.methodName = methodName;
    this.classNameMatcher = new QualifiedClassNameMatcher(qualifiedClassName);
  }

  @Override
  public boolean matches(MethodCallExpr call, CompilationUnit unit) {
    if (!call.getNameAsString().equals(methodName)) {
      return false;
    }
    boolean syntacticMatch =
        call.getScope().map(scope -> isMatchingScope(scope, unit)).orElseGet(() -> isStaticallyImported(unit));
    return syntacticMatch || matchesByResolution(call);
  }

  @Override
  public String describe(MethodCallExpr call) {
    return classNameMatcher.simpleClassName() + "." + methodName + "()";
  }

  private boolean isMatchingScope(Expression scope, CompilationUnit unit) {
    String writtenSimpleName = scope.isNameExpr() ? scope.asNameExpr().getNameAsString() : "";
    return classNameMatcher.matches(writtenSimpleName, scope.toString(), unit);
  }

  /**
   * Handles calls where the declaring class isn't written at the call site at all, e.g.
   * {@code randomInstance.nextInt()} where {@code randomInstance} is a variable — the
   * syntactic checks above have nothing to match against, so this resolves the call via
   * the symbol solver and checks its declaring type directly. Only tried when the
   * syntactic checks fail, so existing purely-static rules (WG001-WG003) never reach
   * this path and are unaffected by it.
   */
  private boolean matchesByResolution(MethodCallExpr call) {
    try {
      ResolvedMethodDeclaration resolved = call.resolve();
      return resolved.declaringType().getQualifiedName().equals(classNameMatcher.qualifiedClassName());
    } catch (RuntimeException e) {
      return false;
    }
  }

  private boolean isStaticallyImported(CompilationUnit unit) {
    String qualifiedClassName = classNameMatcher.qualifiedClassName();
    for (ImportDeclaration importDeclaration : unit.getImports()) {
      if (!importDeclaration.isStatic()) {
        continue;
      }
      String name = importDeclaration.getNameAsString();
      boolean wildcardOnClass = importDeclaration.isAsterisk() && name.equals(qualifiedClassName);
      boolean exactMember = !importDeclaration.isAsterisk() && name.equals(qualifiedClassName + "." + methodName);
      if (wildcardOnClass || exactMember) {
        return true;
      }
    }
    return false;
  }
}
