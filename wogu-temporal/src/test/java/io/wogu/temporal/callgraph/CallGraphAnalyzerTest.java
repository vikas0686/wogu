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
}
