package io.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowImplementationScannerTest {

  @TempDir Path sourceRoot;

  private final WorkflowImplementationScanner scanner = new WorkflowImplementationScanner();

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private List<CompilationUnit> parse() {
    return SourceRootParser.parse(List.of(sourceRoot));
  }

  @Test
  void findsClassImplementingAnAnnotatedWorkflowInterface() throws IOException {
    writeJavaFile(
        "com/example/GreetingWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface GreetingWorkflow {
          String greet(String name);
        }
        """);
    writeJavaFile(
        "com/example/GreetingWorkflowImpl.java",
        """
        package com.example;

        public class GreetingWorkflowImpl implements GreetingWorkflow {
          @Override
          public String greet(String name) {
            return "Hello, " + name;
          }
        }
        """);

    List<ScannedWorkflowClass> found = scanner.scan(parse());

    assertThat(found).extracting(ScannedWorkflowClass::qualifiedName).containsExactly("com.example.GreetingWorkflowImpl");
  }

  @Test
  void fallsBackToAllMethodsAsEntryPointsWhenNoWorkflowMethodIsAnnotated() throws IOException {
    writeJavaFile(
        "com/example/GreetingWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface GreetingWorkflow {
          String greet(String name);
        }
        """);
    writeJavaFile(
        "com/example/GreetingWorkflowImpl.java",
        """
        package com.example;

        public class GreetingWorkflowImpl implements GreetingWorkflow {
          @Override
          public String greet(String name) {
            return "Hello, " + name;
          }
        }
        """);

    List<ScannedWorkflowClass> found = scanner.scan(parse());

    assertThat(found).hasSize(1);
    assertThat(found.get(0).entryPoints()).extracting(m -> m.getNameAsString()).containsExactly("greet");
  }

  @Test
  void selectsOnlyTheWorkflowMethodAsTheEntryPointWhenAnnotated() throws IOException {
    writeJavaFile(
        "com/example/OrderWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;
        import io.temporal.workflow.WorkflowMethod;

        @WorkflowInterface
        public interface OrderWorkflow {
          @WorkflowMethod
          String placeOrder(String customerId);
        }
        """);
    writeJavaFile(
        "com/example/OrderWorkflowImpl.java",
        """
        package com.example;

        public class OrderWorkflowImpl implements OrderWorkflow {
          @Override
          public String placeOrder(String customerId) {
            return helper();
          }

          private String helper() {
            return "order placed";
          }
        }
        """);

    List<ScannedWorkflowClass> found = scanner.scan(parse());

    assertThat(found).hasSize(1);
    assertThat(found.get(0).entryPoints()).extracting(m -> m.getNameAsString()).containsExactly("placeOrder");
  }

  @Test
  void ignoresClassesImplementingAPlainUnannotatedInterface() throws IOException {
    writeJavaFile(
        "com/example/NotAWorkflow.java",
        """
        package com.example;

        public interface NotAWorkflow {
          void doStuff();
        }
        """);
    writeJavaFile(
        "com/example/NotAWorkflowImpl.java",
        """
        package com.example;

        public class NotAWorkflowImpl implements NotAWorkflow {
          public void doStuff() {}
        }
        """);

    assertThat(scanner.scan(parse())).isEmpty();
  }

  @Test
  void findsClassDirectlyAnnotatedWithWorkflowInterface() throws IOException {
    writeJavaFile(
        "com/example/DirectlyAnnotated.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public class DirectlyAnnotated {
          public void run() {}
        }
        """);

    List<ScannedWorkflowClass> found = scanner.scan(parse());

    assertThat(found).extracting(ScannedWorkflowClass::qualifiedName).containsExactly("com.example.DirectlyAnnotated");
  }

  @Test
  void resolvesWorkflowInterfaceViaWildcardImport() throws IOException {
    writeJavaFile(
        "com/example/WildcardWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.*;

        @WorkflowInterface
        public interface WildcardWorkflow {
          void run();
        }
        """);
    writeJavaFile(
        "com/example/WildcardWorkflowImpl.java",
        """
        package com.example;

        public class WildcardWorkflowImpl implements WildcardWorkflow {
          public void run() {}
        }
        """);

    assertThat(scanner.scan(parse()))
        .extracting(ScannedWorkflowClass::qualifiedName)
        .containsExactly("com.example.WildcardWorkflowImpl");
  }

  @Test
  void doesNotTreatASameNamedAnnotationFromAnotherPackageAsTemporals() throws IOException {
    writeJavaFile(
        "com/example/LookalikeWorkflow.java",
        """
        package com.example;

        import com.otherframework.WorkflowInterface;

        @WorkflowInterface
        public interface LookalikeWorkflow {
          void run();
        }
        """);
    writeJavaFile(
        "com/example/LookalikeWorkflowImpl.java",
        """
        package com.example;

        public class LookalikeWorkflowImpl implements LookalikeWorkflow {
          public void run() {}
        }
        """);

    assertThat(scanner.scan(parse())).isEmpty();
  }
}
