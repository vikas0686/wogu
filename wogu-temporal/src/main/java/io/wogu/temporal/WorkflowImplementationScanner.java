package io.wogu.temporal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds Temporal workflow implementation classes across a set of parsed compilation
 * units: classes that implement an interface annotated {@code @WorkflowInterface}, or
 * that carry the annotation themselves.
 *
 * <p>This scanner is shared infrastructure for Temporal validators: any future validator
 * that needs to reason about "code inside a workflow implementation" (not just
 * {@link UUIDRandomValidator}) can reuse it instead of re-implementing the same
 * annotation and inheritance matching.
 *
 * <p>Matching is purely syntactic (see {@link TemporalAnnotations}) and resolves the
 * {@code implements} relationship by simple type name within the scanned compilation
 * units, without a classpath-aware symbol solver. In practice a workflow interface and
 * its implementation are declared in the same project, so this is scoped to what is
 * passed in; interfaces defined only in an external, unparsed dependency are not matched.
 */
public final class WorkflowImplementationScanner {

  private static final String WORKFLOW_INTERFACE_QUALIFIED_NAME = "io.temporal.workflow.WorkflowInterface";

  /**
   * Scans {@code units} for workflow implementation classes.
   *
   * @param units parsed compilation units to scan, typically every {@code .java} file
   *     under a project's source roots
   * @return one entry per workflow implementation class found
   */
  public List<ScannedWorkflowClass> scan(List<CompilationUnit> units) {
    Set<String> workflowInterfaceNames = collectWorkflowInterfaceNames(units);
    List<ScannedWorkflowClass> workflowClasses = new ArrayList<>();

    for (CompilationUnit unit : units) {
      for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
        if (type.isInterface()) {
          continue;
        }
        boolean implementsWorkflowInterface =
            type.getImplementedTypes().stream().anyMatch(t -> workflowInterfaceNames.contains(t.getNameAsString()));
        boolean directlyAnnotated = TemporalAnnotations.isAnnotatedWith(type, unit, WORKFLOW_INTERFACE_QUALIFIED_NAME);

        if (implementsWorkflowInterface || directlyAnnotated) {
          workflowClasses.add(new ScannedWorkflowClass(sourcePathOf(unit), unit, type));
        }
      }
    }
    return workflowClasses;
  }

  private static Set<String> collectWorkflowInterfaceNames(List<CompilationUnit> units) {
    Set<String> names = new HashSet<>();
    for (CompilationUnit unit : units) {
      for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
        if (type.isInterface() && TemporalAnnotations.isAnnotatedWith(type, unit, WORKFLOW_INTERFACE_QUALIFIED_NAME)) {
          names.add(type.getNameAsString());
        }
      }
    }
    return names;
  }

  private static Path sourcePathOf(CompilationUnit unit) {
    return unit.getStorage()
        .map(storage -> storage.getPath())
        .orElseThrow(() -> new IllegalStateException("Compilation unit has no associated source file: " + unit));
  }
}
