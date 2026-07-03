package io.wogu.temporal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

/**
 * Recognizes Temporal SDK annotations on parsed source without requiring the Temporal SDK
 * on the classpath.
 *
 * <p>WoGu's Temporal validators analyze source syntactically: they never resolve types
 * against a real classpath, so an annotation named {@code @WorkflowInterface} is only
 * treated as {@code io.temporal.workflow.WorkflowInterface} when the surrounding file's
 * imports make that unambiguous — an explicit import of the qualified name, a
 * wildcard import of {@code io.temporal.workflow.*}, or the annotation being written out
 * fully qualified. This avoids false positives from unrelated user-defined annotations
 * that happen to share the same simple name.
 */
final class TemporalAnnotations {

  private TemporalAnnotations() {}

  /**
   * Whether {@code type} carries an annotation that unambiguously resolves to
   * {@code qualifiedName} given the imports in {@code unit}.
   */
  static boolean isAnnotatedWith(TypeDeclaration<?> type, CompilationUnit unit, String qualifiedName) {
    String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    for (AnnotationExpr annotation : type.getAnnotations()) {
      String written = annotation.getNameAsString();
      if (written.equals(qualifiedName)) {
        return true;
      }
      if (written.equals(simpleName) && resolvesUnqualifiedName(unit, qualifiedName)) {
        return true;
      }
    }
    return false;
  }

  private static boolean resolvesUnqualifiedName(CompilationUnit unit, String qualifiedName) {
    String packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
    for (ImportDeclaration importDeclaration : unit.getImports()) {
      if (importDeclaration.isStatic()) {
        continue;
      }
      String importedName = importDeclaration.getNameAsString();
      if (importDeclaration.isAsterisk() && importedName.equals(packageName)) {
        return true;
      }
      if (!importDeclaration.isAsterisk() && importedName.equals(qualifiedName)) {
        return true;
      }
    }
    return false;
  }
}
