package io.wogu.temporal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.wogu.temporal.callgraph.CallTarget;

/** Matches calls to {@code java.util.UUID.randomUUID()}, the call WG001 flags. */
final class UuidRandomUuidCallTarget implements CallTarget {

  private static final String UUID_QUALIFIED_NAME = "java.util.UUID";
  private static final String RANDOM_UUID_METHOD = "randomUUID";

  @Override
  public boolean matches(MethodCallExpr call, CompilationUnit unit) {
    if (!call.getNameAsString().equals(RANDOM_UUID_METHOD)) {
      return false;
    }
    return call.getScope().map(scope -> isUuidScope(scope, unit)).orElseGet(() -> isStaticallyImportedRandomUuid(unit));
  }

  @Override
  public String describe(MethodCallExpr call) {
    return "UUID.randomUUID()";
  }

  private static boolean isUuidScope(Expression scope, CompilationUnit unit) {
    if (scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals("UUID")) {
      return importsUuid(unit);
    }
    // Fully qualified inline usage, e.g. java.util.UUID.randomUUID(), parses as a
    // field-access-like scope whose textual form is the qualified name.
    return scope.toString().equals(UUID_QUALIFIED_NAME);
  }

  private static boolean importsUuid(CompilationUnit unit) {
    for (ImportDeclaration importDeclaration : unit.getImports()) {
      if (importDeclaration.isStatic()) {
        continue;
      }
      String name = importDeclaration.getNameAsString();
      if (importDeclaration.isAsterisk() ? name.equals("java.util") : name.equals(UUID_QUALIFIED_NAME)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isStaticallyImportedRandomUuid(CompilationUnit unit) {
    for (ImportDeclaration importDeclaration : unit.getImports()) {
      if (!importDeclaration.isStatic()) {
        continue;
      }
      String name = importDeclaration.getNameAsString();
      boolean wildcardOnUuid = importDeclaration.isAsterisk() && name.equals(UUID_QUALIFIED_NAME);
      boolean exactMember = !importDeclaration.isAsterisk() && name.equals(UUID_QUALIFIED_NAME + "." + RANDOM_UUID_METHOD);
      if (wildcardOnUuid || exactMember) {
        return true;
      }
    }
    return false;
  }
}
