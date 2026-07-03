package io.wogu.temporal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A class found by {@link WorkflowImplementationScanner} to be a Temporal workflow
 * implementation: it implements an interface annotated {@code @WorkflowInterface} (or
 * carries that annotation itself).
 *
 * @param file source file the class was parsed from
 * @param compilationUnit the parsed compilation unit containing the class
 * @param declaration the class declaration itself
 */
record ScannedWorkflowClass(Path file, CompilationUnit compilationUnit, ClassOrInterfaceDeclaration declaration) {

  ScannedWorkflowClass {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(compilationUnit, "compilationUnit");
    Objects.requireNonNull(declaration, "declaration");
  }

  /** Best-effort fully qualified name: the compilation unit's package plus the class's simple name. */
  String qualifiedName() {
    return compilationUnit
        .getPackageDeclaration()
        .map(pkg -> pkg.getNameAsString() + "." + declaration.getNameAsString())
        .orElseGet(declaration::getNameAsString);
  }
}
