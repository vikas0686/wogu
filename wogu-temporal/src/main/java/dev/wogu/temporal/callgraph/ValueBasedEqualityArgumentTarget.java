package dev.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.List;
import java.util.Optional;

/**
 * Matches a call to one specific method whose argument at {@code typeArgumentIndex} is a
 * {@code Class<T>} literal (e.g. {@code Price.class}) naming a type that relies on
 * inherited, identity-based {@code equals()} rather than a real, value-based one.
 *
 * <p>This is the detection primitive behind WG012's {@code mutable-side-effect-equality}
 * declarative rule type: {@code Workflow.mutableSideEffect(id, valueClass, updateFunction,
 * func)}'s {@code updateFunction} can only ever report "unchanged" (skipping a new history
 * event) if {@code valueClass} has real value equality — a type relying on the inherited
 * {@code Object.equals()} is never equal to a freshly constructed instance, even when its
 * fields are identical, so every call appends a new event regardless of whether the logical
 * value actually changed. Built as a reusable, argument-index-parameterized target — like
 * {@link StaticMethodCallTarget}/{@link ConstructorCallTarget} are for their own shape —
 * rather than one hardcoded to {@code mutableSideEffect} specifically, so a future rule
 * needing the same "this argument's type must have real equality" check for a different API
 * reuses this instead of duplicating it.
 */
public final class ValueBasedEqualityArgumentTarget implements CallTarget {

  private final CallTarget methodTarget;
  private final String methodDisplayName;
  private final int typeArgumentIndex;

  /**
   * @param qualifiedMethodReference fully qualified {@code Class.method} reference, e.g.
   *     {@code "io.temporal.workflow.Workflow.mutableSideEffect"}
   * @param typeArgumentIndex 0-based index of the {@code Class<T>} argument to inspect
   */
  public ValueBasedEqualityArgumentTarget(String qualifiedMethodReference, int typeArgumentIndex) {
    int lastDot = qualifiedMethodReference.lastIndexOf('.');
    if (lastDot < 0) {
      throw new IllegalArgumentException(
          "Not a fully qualified Class.method reference: '" + qualifiedMethodReference + "'");
    }
    String className = qualifiedMethodReference.substring(0, lastDot);
    String methodName = qualifiedMethodReference.substring(lastDot + 1);
    this.methodTarget = new StaticMethodCallTarget(className, methodName);
    this.methodDisplayName = className.substring(className.lastIndexOf('.') + 1) + "." + methodName;
    if (typeArgumentIndex < 0) {
      throw new IllegalArgumentException("typeArgumentIndex must be >= 0, was " + typeArgumentIndex);
    }
    this.typeArgumentIndex = typeArgumentIndex;
  }

  @Override
  public boolean matches(MethodCallExpr call, CompilationUnit unit) {
    if (!methodTarget.matches(call, unit)) {
      return false;
    }
    return typeArgument(call).filter(classExpr -> !hasValueBasedEquality(classExpr)).isPresent();
  }

  @Override
  public String describe(MethodCallExpr call) {
    return typeArgument(call)
        .map(classExpr -> methodDisplayName + "(" + classExpr.getType().asString() + ".class, ...)")
        .orElse(methodDisplayName + "(...)");
  }

  private Optional<ClassExpr> typeArgument(MethodCallExpr call) {
    List<Expression> arguments = call.getArguments();
    if (arguments.size() <= typeArgumentIndex) {
      return Optional.empty();
    }
    Expression argument = arguments.get(typeArgumentIndex);
    return argument.isClassExpr() ? Optional.of(argument.asClassExpr()) : Optional.empty();
  }

  /**
   * Whether the type named by {@code classExpr} (e.g. {@code Price} in {@code Price.class})
   * has a real, value-based {@code equals(Object)} — declared directly, inherited from a
   * resolvable ancestor other than {@code java.lang.Object}, or a Java record, which the
   * language always gives a synthesized value-based one. An unresolvable type (a dependency
   * not on WoGu's own classpath, since {@code SourceRootParser} never includes the target
   * project's compiled jars) is treated as "cannot determine," not a violation — the same
   * way an unresolvable call is a traversal boundary rather than an error elsewhere in this
   * engine.
   */
  private static boolean hasValueBasedEquality(ClassExpr classExpr) {
    try {
      ResolvedType resolved = classExpr.getType().resolve();
      if (!resolved.isReferenceType()) {
        return true;
      }
      ResolvedReferenceTypeDeclaration declaration = resolved.asReferenceType().getTypeDeclaration().orElse(null);
      if (declaration == null) {
        return true;
      }
      if (declaration.isRecord()) {
        return true;
      }
      return declaration.getAllMethods().stream()
          .anyMatch(
              method ->
                  method.getName().equals("equals")
                      && method.getNoParams() == 1
                      && method.getParamType(0).describe().equals("java.lang.Object")
                      && !method.declaringType().getQualifiedName().equals("java.lang.Object"));
    } catch (RuntimeException e) {
      return true;
    }
  }
}
