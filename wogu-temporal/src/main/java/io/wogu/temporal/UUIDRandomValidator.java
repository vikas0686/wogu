package io.wogu.temporal;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.wogu.api.Severity;
import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationResult;
import io.wogu.api.Violation;
import io.wogu.api.WorkflowValidator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Flags {@code UUID.randomUUID()} calls inside Temporal workflow implementation classes.
 *
 * <p>Temporal replays workflow code from history to reconstruct state; any source of
 * non-determinism inside workflow code (wall-clock time, random numbers, thread
 * scheduling, etc.) can produce a different result on replay than it did on the original
 * execution, corrupting workflow state. {@code java.util.UUID.randomUUID()} is exactly
 * such a source: it is not seeded from workflow history, so a replay generates a
 * different UUID than the original run did.
 *
 * <p>Temporal provides {@code io.temporal.workflow.Workflow.randomUUID()}, which is
 * backed by a deterministic, replay-safe random number generator seeded from the
 * workflow's run ID. This validator's suggested fix always points there.
 *
 * <p>Detection is limited to classes that {@link WorkflowImplementationScanner}
 * identifies as workflow implementations, so calls to {@code UUID.randomUUID()} in
 * activities, plain application code, or test code are correctly left unflagged.
 */
public final class UUIDRandomValidator implements WorkflowValidator {

  /** Stable id of this validator, see {@link WorkflowValidator#id()}. */
  public static final String ID = "uuid-random-in-workflow";

  private static final String UUID_QUALIFIED_NAME = "java.util.UUID";
  private static final String RANDOM_UUID_METHOD = "randomUUID";

  private static final String MESSAGE =
      "UUID.randomUUID() is non-deterministic and will produce a different value on "
          + "workflow replay, which can corrupt workflow state.";
  private static final String SUGGESTED_FIX =
      "Use io.temporal.workflow.Workflow.randomUUID() instead of java.util.UUID.randomUUID(). "
          + "Workflow.randomUUID() is deterministic across replay.";

  private final WorkflowImplementationScanner scanner = new WorkflowImplementationScanner();

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String description() {
    return "Flags UUID.randomUUID() calls inside Temporal workflow implementation classes, "
        + "which break replay determinism.";
  }

  @Override
  public ValidationResult validate(ValidationContext context) {
    Instant start = Instant.now();
    List<CompilationUnit> units = SourceRootParser.parse(context.sourceRoots());
    List<ScannedWorkflowClass> workflowClasses = scanner.scan(units);

    List<Violation> violations = new ArrayList<>();
    for (ScannedWorkflowClass workflowClass : workflowClasses) {
      for (MethodCallExpr call : workflowClass.declaration().findAll(MethodCallExpr.class)) {
        if (isUuidRandomUuidCall(call, workflowClass.compilationUnit())) {
          violations.add(toViolation(workflowClass, call, context));
        }
      }
    }

    return ValidationResult.of(ID, violations, Duration.between(start, Instant.now()));
  }

  private static boolean isUuidRandomUuidCall(MethodCallExpr call, CompilationUnit unit) {
    if (!call.getNameAsString().equals(RANDOM_UUID_METHOD)) {
      return false;
    }
    return call.getScope()
        .map(scope -> isUuidScope(scope, unit))
        .orElseGet(() -> isStaticallyImportedRandomUuid(unit));
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

  private static Violation toViolation(ScannedWorkflowClass workflowClass, MethodCallExpr call, ValidationContext context) {
    int line = call.getBegin().map(position -> position.line).orElse(1);
    return Violation.builder()
        .validatorId(ID)
        .severity(Severity.ERROR)
        .file(relativize(context.projectDirectory(), workflowClass.file()))
        .className(workflowClass.qualifiedName())
        .line(line)
        .message(MESSAGE)
        .suggestedFix(SUGGESTED_FIX)
        .build();
  }

  /**
   * Renders {@code file} relative to {@code projectDirectory} for readability in reports,
   * falling back to the original path if the two are not comparable (e.g. different
   * filesystem roots).
   */
  private static Path relativize(Path projectDirectory, Path file) {
    try {
      return projectDirectory.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
    } catch (IllegalArgumentException e) {
      return file;
    }
  }
}
