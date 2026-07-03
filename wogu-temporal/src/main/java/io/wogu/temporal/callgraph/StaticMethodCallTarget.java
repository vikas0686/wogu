package io.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

/**
 * A reusable {@link CallTarget} matching calls to one specific static method, identified
 * by its fully qualified class name and method name — e.g.
 * {@code new StaticMethodCallTarget("java.util.UUID", "randomUUID")}.
 *
 * <p>This is the shared detection primitive behind every "flag this specific static
 * method call" rule (WG001's {@code UUID.randomUUID()}, WG002's {@code Thread.sleep()},
 * WG003's non-deterministic time APIs, and any future rule of the same shape): a rule
 * author only supplies the class and method name, not new AST-matching logic.
 *
 * <p>A call matches when it is written as:
 *
 * <ul>
 *   <li>the simple class name as scope (e.g. {@code UUID.randomUUID()}), with either an
 *       explicit import of the qualified name, a wildcard import of its package, or —
 *       for classes in {@code java.lang}, which need no import — no conflicting import of
 *       a different class with the same simple name;
 *   <li>the fully qualified name written inline as scope (e.g.
 *       {@code java.util.UUID.randomUUID()}); or
 *   <li>no scope at all, when the method is statically imported (e.g.
 *       {@code import static java.util.UUID.randomUUID;}).
 * </ul>
 */
public final class StaticMethodCallTarget implements CallTarget {

  private final String qualifiedClassName;
  private final String methodName;
  private final String simpleClassName;
  private final String packageName;
  private final boolean partOfJavaLang;

  /**
   * @param qualifiedClassName fully qualified name of the class the method is declared
   *     on, e.g. {@code "java.lang.Thread"}
   * @param methodName the static method's name, e.g. {@code "sleep"}
   */
  public StaticMethodCallTarget(String qualifiedClassName, String methodName) {
    this.qualifiedClassName = qualifiedClassName;
    this.methodName = methodName;
    int lastDot = qualifiedClassName.lastIndexOf('.');
    this.simpleClassName = qualifiedClassName.substring(lastDot + 1);
    this.packageName = qualifiedClassName.substring(0, lastDot);
    this.partOfJavaLang = packageName.equals("java.lang");
  }

  @Override
  public boolean matches(MethodCallExpr call, CompilationUnit unit) {
    if (!call.getNameAsString().equals(methodName)) {
      return false;
    }
    return call.getScope().map(scope -> isMatchingScope(scope, unit)).orElseGet(() -> isStaticallyImported(unit));
  }

  @Override
  public String describe(MethodCallExpr call) {
    return simpleClassName + "." + methodName + "()";
  }

  private boolean isMatchingScope(Expression scope, CompilationUnit unit) {
    if (scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(simpleClassName)) {
      return partOfJavaLang ? !shadowedByConflictingImport(unit) : importsType(unit);
    }
    // Fully qualified inline usage, e.g. java.lang.Thread.sleep(), parses as a
    // field-access-like scope whose textual form is the qualified name.
    return scope.toString().equals(qualifiedClassName);
  }

  private boolean importsType(CompilationUnit unit) {
    for (ImportDeclaration importDeclaration : unit.getImports()) {
      if (importDeclaration.isStatic()) {
        continue;
      }
      String name = importDeclaration.getNameAsString();
      if (importDeclaration.isAsterisk() ? name.equals(packageName) : name.equals(qualifiedClassName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * For {@code java.lang} classes (which need no import to be visible), only an explicit
   * import of a *different* class with the same simple name should stop us from treating
   * the bare name as this one — otherwise {@code Thread} always means
   * {@code java.lang.Thread} by default.
   */
  private boolean shadowedByConflictingImport(CompilationUnit unit) {
    for (ImportDeclaration importDeclaration : unit.getImports()) {
      if (importDeclaration.isStatic() || importDeclaration.isAsterisk()) {
        continue;
      }
      String name = importDeclaration.getNameAsString();
      if (name.endsWith("." + simpleClassName) && !name.equals(qualifiedClassName)) {
        return true;
      }
    }
    return false;
  }

  private boolean isStaticallyImported(CompilationUnit unit) {
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
