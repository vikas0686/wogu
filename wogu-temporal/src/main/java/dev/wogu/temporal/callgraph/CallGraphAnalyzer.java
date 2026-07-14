package dev.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import dev.wogu.api.CallPathFrame;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reusable call-graph traversal: starting from a method (typically a workflow entry
 * point), follows every resolvable method call reachable from it and reports every call
 * site (a method call or a constructor call) matching a given {@link CallTarget}, along
 * with the full path from the entry point down to that call site.
 *
 * <p>This is the shared engine behind every Temporal rule that needs to know not just
 * "does this class call X directly" but "can this workflow's execution reach X through any
 * number of intermediate method calls" — e.g. WG001 ({@code UUID.randomUUID()}) through
 * WG010 ({@code Executors}/{@code Thread}), and any future rule of the same shape. Each
 * such rule supplies its own {@link CallTarget}; none of them re-implement traversal.
 *
 * <p>Resolution only ever looks at the project's own source (see
 * {@code SourceRootParser}, which configures the symbol solver these {@link MethodCallExpr}
 * nodes were parsed with). A call that cannot be resolved — because it dispatches into a
 * third-party library, uses reflection, is an interface method with multiple possible
 * implementations, or otherwise lacks source WoGu can see — is treated as the edge of what
 * this analysis can see, not as an error: traversal simply stops at that call without
 * reporting anything past it. This keeps the engine free of false positives at the cost of
 * occasionally missing a violation behind an unresolvable call; see {@code CallTarget}
 * implementations for how a direct match still gets reported even when nothing beyond it
 * can be explored further.
 *
 * <p>Separately, traversal also tracks the current {@link ExecutionContext}: a caller
 * supplies a list of {@link ContextEntryPoint}s (e.g. "a call matching
 * {@code Workflow.sideEffect(...)} puts its callback into {@code SIDE_EFFECT}"), and every
 * match reachable from such a callback — however many further method calls deep — carries
 * that context, until traversal returns out of it. The engine itself has no idea what
 * {@code sideEffect} means; it just applies whatever {@link ContextEntryPoint}s it was
 * given, the same way {@code traversalBoundary} lets a caller mark methods opaque without
 * this class knowing what an Activity is.
 */
public final class CallGraphAnalyzer {

  /** Safety bound on traversal depth, guarding against pathological call chains. */
  private static final int MAX_DEPTH = 64;

  /**
   * Finds every call matching {@code target} reachable from {@code entryPoint}.
   *
   * @param entryPoint the method to start traversal from, e.g. a workflow implementation's
   *     entry-point method
   * @param target the call pattern to look for at every call site visited
   * @return one match per matching call site found; empty if none are reachable
   */
  public List<CallGraphMatch> findCallPaths(MethodDeclaration entryPoint, CallTarget target) {
    return findCallPaths(entryPoint, target, method -> false);
  }

  /**
   * Finds every call matching {@code target} reachable from {@code entryPoint}, treating
   * any method for which {@code traversalBoundary} returns {@code true} as opaque: the
   * traversal neither looks inside it for matches nor recurses past it. This is how a
   * caller stops the (Temporal-agnostic) engine from descending into, say, an Activity
   * implementation, without this class needing to know what an Activity is.
   *
   * @param entryPoint the method to start traversal from
   * @param target the call pattern to look for at every call site visited
   * @param traversalBoundary methods this traversal must not enter, checked before a
   *     method's own body is examined at all (including the entry point itself)
   * @return one match per matching call site found; empty if none are reachable
   */
  public List<CallGraphMatch> findCallPaths(
      MethodDeclaration entryPoint, CallTarget target, Predicate<MethodDeclaration> traversalBoundary) {
    return findCallPaths(entryPoint, target, traversalBoundary, List.of());
  }

  /**
   * Finds every call matching {@code target} reachable from {@code entryPoint}, the same
   * as {@link #findCallPaths(MethodDeclaration, CallTarget, Predicate)}, additionally
   * tracking which {@link ExecutionContext} each match was found in.
   *
   * @param entryPoint the method to start traversal from
   * @param target the call pattern to look for at every call site visited
   * @param traversalBoundary methods this traversal must not enter
   * @param contextEntryPoints calls that change the execution context for their
   *     functional-interface argument (e.g. {@code Workflow.sideEffect(...)}); every match
   *     reachable from inside one carries that context, however many hops deep, until
   *     traversal returns out of it
   * @return one match per matching call site found; empty if none are reachable
   */
  public List<CallGraphMatch> findCallPaths(
      MethodDeclaration entryPoint,
      CallTarget target,
      Predicate<MethodDeclaration> traversalBoundary,
      List<ContextEntryPoint> contextEntryPoints) {
    List<CallGraphMatch> results = new ArrayList<>();
    Deque<CallPathFrame> path = new ArrayDeque<>();
    path.addLast(frameFor(entryPoint, lineOf(entryPoint)));
    Set<MethodDeclaration> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    search(
        entryPoint,
        target,
        path,
        visiting,
        results,
        0,
        traversalBoundary,
        contextEntryPoints,
        ExecutionContext.NORMAL_WORKFLOW);
    return results;
  }

  /**
   * Finds every {@code catch} clause reachable from {@code entryPoint} (however many
   * method calls deep) whose caught type matches one of {@code caughtTypeNames} — checked
   * the same way {@link ConstructorCallTarget} matches a constructor's type: an explicit
   * import of the qualified name, a wildcard import of its package, no import needed for a
   * {@code java.lang} type, or the fully qualified name written inline. A multi-catch
   * (e.g. {@code catch (IOException | InterruptedException e)}) is checked component type
   * by component type, so only the matching component is reported.
   *
   * <p>This exists alongside {@link #findCallPaths}, not as an overload of it, because a
   * {@code catch} clause's type is neither a method call nor a constructor call — nothing
   * {@link CallTarget} can match against — while everything else about "reachable from a
   * workflow entry point, tracking {@link ExecutionContext}, however many hops deep" stays
   * identical, so this reuses the same path/visiting/context bookkeeping.
   *
   * @param entryPoint the method to start traversal from
   * @param caughtTypeNames fully qualified names of the types to flag when caught, e.g.
   *     {@code "java.lang.Throwable"}
   * @param traversalBoundary methods this traversal must not enter
   * @param contextEntryPoints calls that establish an {@link ExecutionContext} for their
   *     callback, the same as {@link #findCallPaths(MethodDeclaration, CallTarget, Predicate, List)}
   * @return one match per matching {@code catch} clause found; empty if none are reachable
   */
  public List<CallGraphMatch> findCaughtTypeMatches(
      MethodDeclaration entryPoint,
      List<String> caughtTypeNames,
      Predicate<MethodDeclaration> traversalBoundary,
      List<ContextEntryPoint> contextEntryPoints) {
    List<QualifiedClassNameMatcher> matchers = caughtTypeNames.stream().map(QualifiedClassNameMatcher::new).toList();
    List<CallGraphMatch> results = new ArrayList<>();
    Deque<CallPathFrame> path = new ArrayDeque<>();
    path.addLast(frameFor(entryPoint, lineOf(entryPoint)));
    Set<MethodDeclaration> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    collectCaughtTypeMatches(
        entryPoint, matchers, path, visiting, results, 0, traversalBoundary, contextEntryPoints, ExecutionContext.NORMAL_WORKFLOW);
    return results;
  }

  private void collectCaughtTypeMatches(
      MethodDeclaration method,
      List<QualifiedClassNameMatcher> matchers,
      Deque<CallPathFrame> path,
      Set<MethodDeclaration> visiting,
      List<CallGraphMatch> results,
      int depth,
      Predicate<MethodDeclaration> traversalBoundary,
      List<ContextEntryPoint> contextEntryPoints,
      ExecutionContext inheritedContext) {
    if (traversalBoundary.test(method)) {
      return;
    }
    if (depth > MAX_DEPTH || !visiting.add(method)) {
      return;
    }
    try {
      method
          .findCompilationUnit()
          .ifPresent(
              unit -> {
                for (CatchClause catchClause : method.findAll(CatchClause.class)) {
                  ExecutionContext contextAtCatch =
                      effectiveContext(catchClause, method, unit, contextEntryPoints, inheritedContext);
                  for (ReferenceType caughtType : caughtTypesOf(catchClause)) {
                    matchingType(matchers, caughtType, unit)
                        .ifPresent(matcher -> recordMatch(
                            "catch (" + matcher.simpleClassName() + ")",
                            unit,
                            caughtType,
                            path,
                            results,
                            method,
                            contextAtCatch));
                  }
                }
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                  ExecutionContext contextAtCall =
                      effectiveContext(call, method, unit, contextEntryPoints, inheritedContext);
                  resolveToSource(call).ifPresent(resolved -> {
                    path.addLast(frameFor(resolved, lineOf(call)));
                    collectCaughtTypeMatches(
                        resolved, matchers, path, visiting, results, depth + 1, traversalBoundary, contextEntryPoints,
                        contextAtCall);
                    path.removeLast();
                  });
                }
              });
    } finally {
      visiting.remove(method);
    }
  }

  /**
   * A {@code catch} clause's individual caught types: a single-element list for an
   * ordinary catch, or one element per alternative for a multi-catch (e.g.
   * {@code catch (IOException | InterruptedException e)}).
   */
  private static List<ReferenceType> caughtTypesOf(CatchClause catchClause) {
    Type type = catchClause.getParameter().getType();
    return type.isUnionType() ? type.asUnionType().getElements() : List.of((ReferenceType) type);
  }

  private static Optional<QualifiedClassNameMatcher> matchingType(
      List<QualifiedClassNameMatcher> matchers, ReferenceType caughtType, CompilationUnit unit) {
    if (!(caughtType instanceof ClassOrInterfaceType classType)) {
      return Optional.empty();
    }
    String writtenSimpleName = classType.getScope().isPresent() ? "" : classType.getNameAsString();
    String writtenFullText = classType.toString();
    return matchers.stream().filter(matcher -> matcher.matches(writtenSimpleName, writtenFullText, unit)).findFirst();
  }

  private void search(
      MethodDeclaration method,
      CallTarget target,
      Deque<CallPathFrame> path,
      Set<MethodDeclaration> visiting,
      List<CallGraphMatch> results,
      int depth,
      Predicate<MethodDeclaration> traversalBoundary,
      List<ContextEntryPoint> contextEntryPoints,
      ExecutionContext inheritedContext) {
    if (traversalBoundary.test(method)) {
      return;
    }
    if (depth > MAX_DEPTH || !visiting.add(method)) {
      return;
    }
    try {
      method
          .findCompilationUnit()
          .ifPresent(
              unit -> {
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                  ExecutionContext contextAtCall =
                      effectiveContext(call, method, unit, contextEntryPoints, inheritedContext);
                  if (target.matches(call, unit)) {
                    recordMatch(target.describe(call), unit, call, path, results, method, contextAtCall);
                    continue;
                  }
                  resolveToSource(call).ifPresent(resolved -> {
                    path.addLast(frameFor(resolved, lineOf(call)));
                    search(
                        resolved, target, path, visiting, results, depth + 1, traversalBoundary, contextEntryPoints, contextAtCall);
                    path.removeLast();
                  });
                }
                for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
                  if (target.matchesConstructor(creation, unit)) {
                    ExecutionContext contextAtCreation =
                        effectiveContext(creation, method, unit, contextEntryPoints, inheritedContext);
                    recordMatch(
                        target.describeConstructor(creation), unit, creation, path, results, method, contextAtCreation);
                  }
                }
              });
    } finally {
      visiting.remove(method);
    }
  }

  /**
   * The {@link ExecutionContext} in effect at {@code node}: {@code inheritedContext} (how
   * traversal got here) unless {@code node} lies inside one of {@code contextEntryPoints}'
   * matching calls' functional-interface argument somewhere within {@code enclosingMethod}
   * itself, in which case the innermost such wrapper wins — a context established closer
   * to this exact call site is more specific than whatever was inherited from further up
   * the call chain.
   *
   * <p>Only recognizes the callback as an inline expression argument (a lambda, in
   * practice) written directly at the call site, since that is what
   * {@code Workflow.sideEffect(...)} and {@code Workflow.mutableSideEffect(...)} are
   * used with in practice. A method reference or a variable holding a pre-built
   * {@code Func}/{@code Supplier} is not recognized; this can be extended if a real rule
   * ever needs it.
   */
  private static ExecutionContext effectiveContext(
      Node node,
      MethodDeclaration enclosingMethod,
      CompilationUnit unit,
      List<ContextEntryPoint> contextEntryPoints,
      ExecutionContext inheritedContext) {
    if (contextEntryPoints.isEmpty()) {
      return inheritedContext;
    }
    Node current = node;
    Optional<Node> parent = current.getParentNode();
    while (parent.isPresent() && parent.get() != enclosingMethod) {
      Node parentNode = parent.get();
      if (parentNode instanceof MethodCallExpr enclosingCall && containsByIdentity(enclosingCall.getArguments(), current)) {
        for (ContextEntryPoint entryPoint : contextEntryPoints) {
          if (entryPoint.matcher().matches(enclosingCall, unit)) {
            return entryPoint.context();
          }
        }
      }
      current = parentNode;
      parent = current.getParentNode();
    }
    return inheritedContext;
  }

  private static boolean containsByIdentity(Iterable<? extends Node> nodes, Node target) {
    for (Node candidate : nodes) {
      if (candidate == target) {
        return true;
      }
    }
    return false;
  }

  private static void recordMatch(
      String description,
      CompilationUnit unit,
      Node matchedNode,
      Deque<CallPathFrame> path,
      List<CallGraphMatch> results,
      MethodDeclaration containingMethod,
      ExecutionContext executionContext) {
    Path file = sourceFileOf(unit);
    int line = lineOf(matchedNode);
    List<CallPathFrame> found = new ArrayList<>(path);
    found.add(new CallPathFrame(description, file, line));
    results.add(new CallGraphMatch(found, classNameOf(containingMethod), file, line, executionContext));
  }

  private static Optional<MethodDeclaration> resolveToSource(MethodCallExpr call) {
    try {
      ResolvedMethodDeclaration resolved = call.resolve();
      if (resolved instanceof JavaParserMethodDeclaration javaParserMethod) {
        return Optional.of(javaParserMethod.getWrappedNode());
      }
    } catch (RuntimeException e) {
      // Unresolvable: reflection, dynamic dispatch, a third-party library, or missing
      // source. This is an expected traversal boundary, not an analysis failure.
    }
    return Optional.empty();
  }

  private static CallPathFrame frameFor(MethodDeclaration method, int line) {
    String displayName = classNameOf(method) + "." + method.getNameAsString() + "()";
    Path file = method.findCompilationUnit().map(CallGraphAnalyzer::sourceFileOf).orElse(Path.of("<unknown>"));
    return new CallPathFrame(displayName, file, line);
  }

  private static String classNameOf(MethodDeclaration method) {
    return method.findAncestor(ClassOrInterfaceDeclaration.class).map(NodeWithSimpleName::getNameAsString).orElse("?");
  }

  private static Path sourceFileOf(CompilationUnit unit) {
    return unit.getStorage()
        .map(storage -> storage.getPath())
        .orElseThrow(() -> new IllegalStateException("Compilation unit has no associated source file: " + unit));
  }

  private static int lineOf(Node node) {
    return node.getBegin().map(position -> position.line).orElse(1);
  }
}
