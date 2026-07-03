package io.wogu.temporal.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import io.wogu.api.CallPathFrame;
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
    List<CallGraphMatch> results = new ArrayList<>();
    Deque<CallPathFrame> path = new ArrayDeque<>();
    path.addLast(frameFor(entryPoint, lineOf(entryPoint)));
    Set<MethodDeclaration> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    search(entryPoint, target, path, visiting, results, 0, traversalBoundary);
    return results;
  }

  private void search(
      MethodDeclaration method,
      CallTarget target,
      Deque<CallPathFrame> path,
      Set<MethodDeclaration> visiting,
      List<CallGraphMatch> results,
      int depth,
      Predicate<MethodDeclaration> traversalBoundary) {
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
                  if (target.matches(call, unit)) {
                    recordMatch(target.describe(call), unit, call, path, results, method);
                    continue;
                  }
                  resolveToSource(call).ifPresent(resolved -> {
                    path.addLast(frameFor(resolved, lineOf(call)));
                    search(resolved, target, path, visiting, results, depth + 1, traversalBoundary);
                    path.removeLast();
                  });
                }
                for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
                  if (target.matchesConstructor(creation, unit)) {
                    recordMatch(target.describeConstructor(creation), unit, creation, path, results, method);
                  }
                }
              });
    } finally {
      visiting.remove(method);
    }
  }

  private static void recordMatch(
      String description,
      CompilationUnit unit,
      Node matchedNode,
      Deque<CallPathFrame> path,
      List<CallGraphMatch> results,
      MethodDeclaration containingMethod) {
    Path file = sourceFileOf(unit);
    int line = lineOf(matchedNode);
    List<CallPathFrame> found = new ArrayList<>(path);
    found.add(new CallPathFrame(description, file, line));
    results.add(new CallGraphMatch(found, classNameOf(containingMethod), file, line));
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
