package io.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

/**
 * A reusable {@link CallTarget} matching construction of one specific class, identified
 * by its fully qualified name — e.g. {@code new ConstructorCallTarget("java.util.Random")}
 * matches {@code new Random()}.
 *
 * <p>Shares its class-name-matching rules (explicit import, wildcard import,
 * {@code java.lang} needing no import, fully qualified inline usage) with
 * {@link StaticMethodCallTarget} via {@link QualifiedClassNameMatcher}, so a constructor
 * target and a method-call target for the same class agree on what counts as "this
 * class" without duplicating that logic.
 */
public final class ConstructorCallTarget implements CallTarget {

  private final QualifiedClassNameMatcher classNameMatcher;

  /**
   * @param qualifiedClassName fully qualified name of the constructed class, e.g.
   *     {@code "java.util.Random"}
   */
  public ConstructorCallTarget(String qualifiedClassName) {
    this.classNameMatcher = new QualifiedClassNameMatcher(qualifiedClassName);
  }

  @Override
  public boolean matches(MethodCallExpr call, CompilationUnit unit) {
    return false;
  }

  @Override
  public String describe(MethodCallExpr call) {
    throw new UnsupportedOperationException(getClass().getName() + " does not match method calls");
  }

  @Override
  public boolean matchesConstructor(ObjectCreationExpr creation, CompilationUnit unit) {
    // Unlike a method call's scope (a NameExpr for a bare name vs. a FieldAccessExpr chain
    // for a qualified one), a constructor's type always reports its simple name from
    // getNameAsString() even when written fully qualified — getScope() is what reveals
    // whether a package qualifier was written at all.
    String writtenSimpleName = creation.getType().getScope().isPresent() ? "" : creation.getType().getNameAsString();
    return classNameMatcher.matches(writtenSimpleName, creation.getType().toString(), unit);
  }

  @Override
  public String describeConstructor(ObjectCreationExpr creation) {
    return "new " + classNameMatcher.simpleClassName() + "()";
  }
}
