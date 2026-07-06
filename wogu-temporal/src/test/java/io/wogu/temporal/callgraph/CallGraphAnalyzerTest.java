package io.wogu.temporal.callgraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import io.wogu.api.CallPathFrame;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallGraphAnalyzerTest {

  private static final CallTarget RANDOM_CONSTRUCTOR = new ConstructorCallTarget("java.util.Random");

  private static final List<ContextEntryPoint> SIDE_EFFECT_ENTRY_POINTS =
      List.of(
          new ContextEntryPoint(
              new StaticMethodCallTarget("io.temporal.workflow.Workflow", "sideEffect"), ExecutionContext.SIDE_EFFECT));

  private static final CallTarget UUID_RANDOM_UUID =
      new CallTarget() {
        @Override
        public boolean matches(MethodCallExpr call, CompilationUnit unit) {
          return call.getNameAsString().equals("randomUUID");
        }

        @Override
        public String describe(MethodCallExpr call) {
          return "UUID.randomUUID()";
        }
      };

  @TempDir Path sourceRoot;

  private final CallGraphAnalyzer analyzer = new CallGraphAnalyzer();

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  /** Parses {@code sourceRoot} with a symbol solver configured, the same way {@code SourceRootParser} does. */
  private List<CompilationUnit> parse() {
    CombinedTypeSolver typeSolver = new CombinedTypeSolver();
    JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
    ParserConfiguration configuration = new ParserConfiguration().setSymbolResolver(symbolSolver);
    typeSolver.add(new ReflectionTypeSolver());
    typeSolver.add(new JavaParserTypeSolver(sourceRoot, configuration));
    SourceRoot root = new SourceRoot(sourceRoot, configuration);
    try {
      return root.tryToParse().stream()
          .filter(result -> result.isSuccessful() && result.getResult().isPresent())
          .map(result -> result.getResult().get())
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static MethodDeclaration methodNamed(List<CompilationUnit> units, String className, String methodName) {
    for (CompilationUnit unit : units) {
      for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
        if (!type.getNameAsString().equals(className)) {
          continue;
        }
        for (MethodDeclaration method : type.getMethods()) {
          if (method.getNameAsString().equals(methodName)) {
            return method;
          }
        }
      }
    }
    throw new IllegalArgumentException("No method %s.%s found in parsed units".formatted(className, methodName));
  }

  @Test
  void findsADirectMatchInTheEntryPointItself() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import java.util.UUID;

        class Foo {
          void run() {
            UUID.randomUUID();
          }
        }
        """);

    MethodDeclaration entry = methodNamed(parse(), "Foo", "run");
    List<CallGraphMatch> matches = analyzer.findCallPaths(entry, UUID_RANDOM_UUID);

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).containingClassName()).isEqualTo("Foo");
    assertThat(matches.get(0).path())
        .extracting(CallPathFrame::displayName)
        .containsExactly("Foo.run()", "UUID.randomUUID()");
  }

  @Test
  void followsAMultiHopCallChainAcrossClasses() throws IOException {
    writeJavaFile(
        "PaymentWorkflowImpl.java",
        """
        class PaymentWorkflowImpl {
          private final OrderService orderService = new OrderService();

          void processPayment() {
            orderService.createOrder();
          }
        }
        """);
    writeJavaFile(
        "OrderService.java",
        """
        class OrderService {
          private final CustomerService customerService = new CustomerService();

          void createOrder() {
            customerService.generateId();
          }
        }
        """);
    writeJavaFile(
        "CustomerService.java",
        """
        import java.util.UUID;

        class CustomerService {
          void generateId() {
            UUID.randomUUID();
          }
        }
        """);

    List<CompilationUnit> units = parse();
    MethodDeclaration entry = methodNamed(units, "PaymentWorkflowImpl", "processPayment");
    List<CallGraphMatch> matches = analyzer.findCallPaths(entry, UUID_RANDOM_UUID);

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).containingClassName()).isEqualTo("CustomerService");
    assertThat(matches.get(0).path())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.processPayment()",
            "OrderService.createOrder()",
            "CustomerService.generateId()",
            "UUID.randomUUID()");
  }

  @Test
  void stopsAtAnUnresolvableCallWithoutFailing() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        class Foo {
          void run() {
            new com.thirdparty.SomeLibrary().doSomething();
          }
        }
        """);

    MethodDeclaration entry = methodNamed(parse(), "Foo", "run");
    List<CallGraphMatch> matches = analyzer.findCallPaths(entry, UUID_RANDOM_UUID);

    assertThat(matches).isEmpty();
  }

  @Test
  void doesNotInfiniteLoopOnARecursiveCall() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        class Foo {
          void run(int depth) {
            if (depth > 0) {
              run(depth - 1);
            }
          }
        }
        """);

    MethodDeclaration entry = methodNamed(parse(), "Foo", "run");
    List<CallGraphMatch> matches = analyzer.findCallPaths(entry, UUID_RANDOM_UUID);

    assertThat(matches).isEmpty();
  }

  @Test
  void findsMultipleIndependentMatchesInDifferentBranches() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import java.util.UUID;

        class Foo {
          void run() {
            first();
            second();
          }

          void first() {
            UUID.randomUUID();
          }

          void second() {
            UUID.randomUUID();
          }
        }
        """);

    MethodDeclaration entry = methodNamed(parse(), "Foo", "run");
    List<CallGraphMatch> matches = analyzer.findCallPaths(entry, UUID_RANDOM_UUID);

    assertThat(matches).hasSize(2);
  }

  @Test
  void findsAConstructorMatchInTheEntryPointItself() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import java.util.Random;

        class Foo {
          void run() {
            new Random();
          }
        }
        """);

    MethodDeclaration entry = methodNamed(parse(), "Foo", "run");
    List<CallGraphMatch> matches = analyzer.findCallPaths(entry, RANDOM_CONSTRUCTOR);

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).path()).extracting(CallPathFrame::displayName).containsExactly("Foo.run()", "new Random()");
  }

  @Test
  void findsAConstructorMatchReachableThroughAMultiHopCallChain() throws IOException {
    writeJavaFile(
        "PaymentWorkflowImpl.java",
        """
        class PaymentWorkflowImpl {
          private final OrderService orderService = new OrderService();

          void processPayment() {
            orderService.createOrder();
          }
        }
        """);
    writeJavaFile(
        "OrderService.java",
        """
        import java.util.Random;

        class OrderService {
          void createOrder() {
            new Random();
          }
        }
        """);

    List<CompilationUnit> units = parse();
    MethodDeclaration entry = methodNamed(units, "PaymentWorkflowImpl", "processPayment");
    List<CallGraphMatch> matches = analyzer.findCallPaths(entry, RANDOM_CONSTRUCTOR);

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).containingClassName()).isEqualTo("OrderService");
    assertThat(matches.get(0).path())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.processPayment()", "OrderService.createOrder()", "new Random()");
  }

  @Test
  void aTraversalBoundaryStopsRecursionIntoAMatchingMethodWithoutReportingItsCalls() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        class Foo {
          private final Activity activity = new Activity();

          void run() {
            activity.doWork();
          }
        }
        """);
    writeJavaFile(
        "Activity.java",
        """
        import java.util.UUID;

        class Activity {
          void doWork() {
            UUID.randomUUID();
          }
        }
        """);

    List<CompilationUnit> units = parse();
    MethodDeclaration entry = methodNamed(units, "Foo", "run");
    List<CallGraphMatch> matches =
        analyzer.findCallPaths(entry, UUID_RANDOM_UUID, method -> method.getNameAsString().equals("doWork"));

    assertThat(matches).isEmpty();
  }

  @Test
  void aTraversalBoundaryDoesNotAffectMethodsOutsideIt() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import java.util.UUID;

        class Foo {
          private final Activity activity = new Activity();

          void run() {
            activity.doWork();
            UUID.randomUUID();
          }
        }
        """);
    writeJavaFile(
        "Activity.java",
        """
        class Activity {
          void doWork() {
          }
        }
        """);

    List<CompilationUnit> units = parse();
    MethodDeclaration entry = methodNamed(units, "Foo", "run");
    List<CallGraphMatch> matches =
        analyzer.findCallPaths(entry, UUID_RANDOM_UUID, method -> method.getNameAsString().equals("doWork"));

    assertThat(matches).hasSize(1);
  }

  @Test
  void matchesDefaultToNormalWorkflowContextWhenNoContextEntryPointsAreSupplied() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import java.util.UUID;

        class Foo {
          void run() {
            UUID.randomUUID();
          }
        }
        """);

    MethodDeclaration entry = methodNamed(parse(), "Foo", "run");
    List<CallGraphMatch> matches = analyzer.findCallPaths(entry, UUID_RANDOM_UUID);

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).executionContext()).isEqualTo(ExecutionContext.NORMAL_WORKFLOW);
  }

  @Test
  void callsInsideAContextEntryPointsCallbackCarryItsExecutionContext() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import io.temporal.workflow.Workflow;
        import java.util.UUID;

        class Foo {
          void run() {
            Workflow.sideEffect(String.class, () -> UUID.randomUUID().toString());
          }
        }
        """);

    MethodDeclaration entry = methodNamed(parse(), "Foo", "run");
    List<CallGraphMatch> matches =
        analyzer.findCallPaths(entry, UUID_RANDOM_UUID, method -> false, SIDE_EFFECT_ENTRY_POINTS);

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).executionContext()).isEqualTo(ExecutionContext.SIDE_EFFECT);
  }

  @Test
  void callsOutsideAContextEntryPointsCallbackKeepTheNormalWorkflowContext() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import java.util.UUID;

        class Foo {
          void run() {
            UUID.randomUUID();
          }
        }
        """);

    MethodDeclaration entry = methodNamed(parse(), "Foo", "run");
    List<CallGraphMatch> matches =
        analyzer.findCallPaths(entry, UUID_RANDOM_UUID, method -> false, SIDE_EFFECT_ENTRY_POINTS);

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).executionContext()).isEqualTo(ExecutionContext.NORMAL_WORKFLOW);
  }

  @Test
  void aContextEntryPointsExecutionContextPropagatesThroughAResolvedCallReachedFromItsCallback() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import io.temporal.workflow.Workflow;

        class Foo {
          private final Service service = new Service();

          void run() {
            Workflow.sideEffect(String.class, () -> service.generateId());
          }
        }
        """);
    writeJavaFile(
        "Service.java",
        """
        import java.util.UUID;

        class Service {
          String generateId() {
            return UUID.randomUUID().toString();
          }
        }
        """);

    List<CompilationUnit> units = parse();
    MethodDeclaration entry = methodNamed(units, "Foo", "run");
    List<CallGraphMatch> matches =
        analyzer.findCallPaths(entry, UUID_RANDOM_UUID, method -> false, SIDE_EFFECT_ENTRY_POINTS);

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).containingClassName()).isEqualTo("Service");
    assertThat(matches.get(0).executionContext()).isEqualTo(ExecutionContext.SIDE_EFFECT);
  }

  @Test
  void aMethodCalledBothInsideAndOutsideACallbackIsReportedOnceForEachContext() throws IOException {
    writeJavaFile(
        "Foo.java",
        """
        import io.temporal.workflow.Workflow;

        class Foo {
          private final Service service = new Service();

          void run() {
            Workflow.sideEffect(String.class, () -> service.generateId());
            service.generateId();
          }
        }
        """);
    writeJavaFile(
        "Service.java",
        """
        import java.util.UUID;

        class Service {
          String generateId() {
            return UUID.randomUUID().toString();
          }
        }
        """);

    List<CompilationUnit> units = parse();
    MethodDeclaration entry = methodNamed(units, "Foo", "run");
    List<CallGraphMatch> matches =
        analyzer.findCallPaths(entry, UUID_RANDOM_UUID, method -> false, SIDE_EFFECT_ENTRY_POINTS);

    assertThat(matches).extracting(CallGraphMatch::executionContext)
        .containsExactlyInAnyOrder(ExecutionContext.SIDE_EFFECT, ExecutionContext.NORMAL_WORKFLOW);
  }
}
