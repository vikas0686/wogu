package io.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

/**
 * Shared "does this name, as written at a use site in this compilation unit, refer to
 * this specific qualified class" check: explicit import of the qualified name, wildcard
 * import of its package, {@code java.lang} classes needing no import at all (unless
 * shadowed by a conflicting same-simple-name import), or the fully qualified name written
 * inline.
 *
 * <p>Used by both {@link StaticMethodCallTarget} (matching a static call's scope) and
 * {@link ConstructorCallTarget} (matching a {@code new} expression's type), so the two
 * agree on what counts as "this class" without duplicating the rule.
 */
final class QualifiedClassNameMatcher {

  private final String qualifiedClassName;
  private final String simpleClassName;
  private final String packageName;
  private final boolean partOfJavaLang;

  QualifiedClassNameMatcher(String qualifiedClassName) {
    this.qualifiedClassName = qualifiedClassName;
    int lastDot = qualifiedClassName.lastIndexOf('.');
    this.simpleClassName = qualifiedClassName.substring(lastDot + 1);
    this.packageName = qualifiedClassName.substring(0, lastDot);
    this.partOfJavaLang = packageName.equals("java.lang");
  }

  String qualifiedClassName() {
    return qualifiedClassName;
  }

  String simpleClassName() {
    return simpleClassName;
  }

  /**
   * @param writtenSimpleName the simple name as written at the use site (e.g. a
   *     {@code NameExpr}'s name, or a constructor type's {@code getNameAsString()}); pass
   *     a value that can never equal a real simple class name (e.g. {@code ""}) if the
   *     use site has no simple-name form
   * @param writtenFullText the full text as written at the use site (e.g.
   *     {@code scope.toString()} or the constructor type's {@code toString()}), used to
   *     catch a fully qualified inline reference
   */
  boolean matches(String writtenSimpleName, String writtenFullText, CompilationUnit unit) {
    if (writtenSimpleName.equals(simpleClassName)) {
      return partOfJavaLang ? !shadowedByConflictingImport(unit) : importsType(unit);
    }
    return writtenFullText.equals(qualifiedClassName);
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
}
