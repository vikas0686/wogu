package dev.wogu.temporal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Identifies methods belonging to a Temporal Activity implementation, so a
 * {@code CallGraphAnalyzer} traversal can stop at that boundary: a rule like WG001 must
 * not report a violation that only occurs inside an Activity, since Activities execute
 * outside workflow replay and are not subject to the same determinism constraints.
 *
 * <p>Mirrors {@link WorkflowImplementationScanner}'s two-pass approach (collect
 * annotated interface names, then match implementing classes by simple name) and reuses
 * {@link TemporalAnnotations} for the same import-aware annotation matching, rather than
 * introducing a second way to recognize Temporal annotations.
 *
 * <p>This only guards against traversal reaching an Activity implementation through a
 * resolvable call (e.g. a field or variable typed as the impl class directly). A call
 * through the Activity *interface* type is already a dead end on its own: it resolves to
 * the interface's bodyless method declaration, which has no calls inside it to recurse
 * into or match against.
 */
final class ActivityAwareness {

  private static final String ACTIVITY_INTERFACE_QUALIFIED_NAME = "io.temporal.activity.ActivityInterface";
  private static final String ACTIVITY_METHOD_QUALIFIED_NAME = "io.temporal.activity.ActivityMethod";

  private ActivityAwareness() {}

  /**
   * Builds a predicate matching every method that is part of an Activity implementation:
   * declared in a class implementing an {@code @ActivityInterface}-annotated interface (or
   * itself annotated {@code @ActivityInterface}), or directly annotated
   * {@code @ActivityMethod}.
   *
   * @param units every parsed compilation unit in the project, scanned once so this
   *     predicate can be reused across every rule's traversal without re-scanning
   */
  static Predicate<MethodDeclaration> activityBoundary(List<CompilationUnit> units) {
    Set<String> activityInterfaceNames = collectActivityInterfaceNames(units);
    return method -> isActivityMethod(method, activityInterfaceNames);
  }

  private static Set<String> collectActivityInterfaceNames(List<CompilationUnit> units) {
    Set<String> names = new HashSet<>();
    for (CompilationUnit unit : units) {
      for (ClassOrInterfaceDeclaration type : new ArrayList<>(unit.findAll(ClassOrInterfaceDeclaration.class))) {
        if (type.isInterface() && TemporalAnnotations.isAnnotatedWith(type, unit, ACTIVITY_INTERFACE_QUALIFIED_NAME)) {
          names.add(type.getNameAsString());
        }
      }
    }
    return names;
  }

  private static boolean isActivityMethod(MethodDeclaration method, Set<String> activityInterfaceNames) {
    CompilationUnit unit = method.findCompilationUnit().orElse(null);
    if (unit == null) {
      return false;
    }
    if (TemporalAnnotations.isAnnotatedWith(method, unit, ACTIVITY_METHOD_QUALIFIED_NAME)) {
      return true;
    }
    return method.findAncestor(ClassOrInterfaceDeclaration.class).map(type -> isActivityType(type, unit, activityInterfaceNames)).orElse(false);
  }

  private static boolean isActivityType(
      ClassOrInterfaceDeclaration type, CompilationUnit unit, Set<String> activityInterfaceNames) {
    if (TemporalAnnotations.isAnnotatedWith(type, unit, ACTIVITY_INTERFACE_QUALIFIED_NAME)) {
      return true;
    }
    return type.getImplementedTypes().stream().anyMatch(t -> activityInterfaceNames.contains(t.getNameAsString()));
  }
}
