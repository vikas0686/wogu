package io.wogu.temporal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds Temporal workflow implementation classes across a set of parsed compilation
 * units: classes that implement an interface annotated {@code @WorkflowInterface}, or
 * that carry the annotation themselves — and, for each, the entry-point method(s) a
 * {@code CallGraphAnalyzer} traversal should start from.
 *
 * <p>This scanner is shared infrastructure for Temporal rules: any future rule that needs
 * to reason about "code reachable from a workflow" (not just WG001) reuses it instead of
 * re-implementing the same annotation and inheritance matching.
 *
 * <p>Matching is purely syntactic (see {@link TemporalAnnotations}) and resolves the
 * {@code implements} relationship by simple type name within the scanned compilation
 * units, without a classpath-aware symbol solver. In practice a workflow interface and
 * its implementation are declared in the same project, so this is scoped to what is
 * passed in; interfaces defined only in an external, unparsed dependency are not matched.
 */
public final class WorkflowImplementationScanner {

  private static final String WORKFLOW_INTERFACE_QUALIFIED_NAME = "io.temporal.workflow.WorkflowInterface";
  private static final String WORKFLOW_METHOD_QUALIFIED_NAME = "io.temporal.workflow.WorkflowMethod";

  /**
   * Scans {@code units} for workflow implementation classes.
   *
   * @param units parsed compilation units to scan, typically every {@code .java} file
   *     under a project's source roots
   * @return one entry per workflow implementation class found
   */
  public List<ScannedWorkflowClass> scan(List<CompilationUnit> units) {
    Map<String, ClassOrInterfaceDeclaration> workflowInterfacesByName = collectWorkflowInterfaces(units);
    List<ScannedWorkflowClass> workflowClasses = new ArrayList<>();

    for (CompilationUnit unit : units) {
      for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
        if (type.isInterface()) {
          continue;
        }
        List<ClassOrInterfaceDeclaration> implementedWorkflowInterfaces =
            type.getImplementedTypes().stream()
                .map(t -> workflowInterfacesByName.get(t.getNameAsString()))
                .filter(java.util.Objects::nonNull)
                .toList();
        boolean directlyAnnotated = TemporalAnnotations.isAnnotatedWith(type, unit, WORKFLOW_INTERFACE_QUALIFIED_NAME);

        if (!implementedWorkflowInterfaces.isEmpty() || directlyAnnotated) {
          List<MethodDeclaration> entryPoints = entryPointsFor(type, implementedWorkflowInterfaces);
          workflowClasses.add(new ScannedWorkflowClass(sourcePathOf(unit), unit, type, entryPoints));
        }
      }
    }
    return workflowClasses;
  }

  /**
   * Entry points for {@code impl}: its methods matching (by name and parameter count) an
   * {@code @WorkflowMethod}-annotated method on any of {@code implementedWorkflowInterfaces}.
   * Falls back to every method declared directly in {@code impl} when none is found, so a
   * workflow that doesn't use {@code @WorkflowMethod} is still fully scannable.
   */
  private static List<MethodDeclaration> entryPointsFor(
      ClassOrInterfaceDeclaration impl, List<ClassOrInterfaceDeclaration> implementedWorkflowInterfaces) {
    List<MethodDeclaration> matched = new ArrayList<>();
    for (ClassOrInterfaceDeclaration workflowInterface : implementedWorkflowInterfaces) {
      CompilationUnit interfaceUnit = workflowInterface.findCompilationUnit().orElse(null);
      if (interfaceUnit == null) {
        continue;
      }
      for (MethodDeclaration interfaceMethod : workflowInterface.getMethods()) {
        if (!TemporalAnnotations.isAnnotatedWith(interfaceMethod, interfaceUnit, WORKFLOW_METHOD_QUALIFIED_NAME)) {
          continue;
        }
        for (MethodDeclaration implMethod : impl.getMethods()) {
          if (implMethod.getNameAsString().equals(interfaceMethod.getNameAsString())
              && implMethod.getParameters().size() == interfaceMethod.getParameters().size()
              && !containsByIdentity(matched, implMethod)) {
            matched.add(implMethod);
          }
        }
      }
    }
    return matched.isEmpty() ? List.copyOf(impl.getMethods()) : List.copyOf(matched);
  }

  /**
   * Identity-based containment check: {@link MethodDeclaration#equals(Object)} performs a
   * structural comparison, which would incorrectly treat two distinct but
   * identical-looking methods as duplicates.
   */
  private static boolean containsByIdentity(List<MethodDeclaration> list, MethodDeclaration target) {
    for (MethodDeclaration candidate : list) {
      if (candidate == target) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, ClassOrInterfaceDeclaration> collectWorkflowInterfaces(List<CompilationUnit> units) {
    Map<String, ClassOrInterfaceDeclaration> interfaces = new HashMap<>();
    for (CompilationUnit unit : units) {
      for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
        if (type.isInterface() && TemporalAnnotations.isAnnotatedWith(type, unit, WORKFLOW_INTERFACE_QUALIFIED_NAME)) {
          interfaces.put(type.getNameAsString(), type);
        }
      }
    }
    return interfaces;
  }

  private static Path sourcePathOf(CompilationUnit unit) {
    return unit.getStorage()
        .map(storage -> storage.getPath())
        .orElseThrow(() -> new IllegalStateException("Compilation unit has no associated source file: " + unit));
  }
}
