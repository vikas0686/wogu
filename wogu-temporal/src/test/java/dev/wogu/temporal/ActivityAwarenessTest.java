package dev.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityAwarenessTest {

  @TempDir Path sourceRoot;

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private List<CompilationUnit> parse() {
    return SourceRootParser.parse(List.of(sourceRoot));
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
  void treatsAMethodOnAClassImplementingAnActivityInterfaceAsABoundary() throws IOException {
    writeJavaFile(
        "PaymentActivity.java",
        """
        import io.temporal.activity.ActivityInterface;
        import io.temporal.activity.ActivityMethod;

        @ActivityInterface
        public interface PaymentActivity {
          @ActivityMethod
          void processPaymentActivity(String accountId);
        }
        """);
    writeJavaFile(
        "PaymentActivityImpl.java",
        """
        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public void processPaymentActivity(String accountId) {
          }
        }
        """);

    List<CompilationUnit> units = parse();
    Predicate<MethodDeclaration> boundary = ActivityAwareness.activityBoundary(units);
    MethodDeclaration implMethod = methodNamed(units, "PaymentActivityImpl", "processPaymentActivity");

    assertThat(boundary.test(implMethod)).isTrue();
  }

  @Test
  void treatsTheInterfaceMethodItselfAsABoundary() throws IOException {
    writeJavaFile(
        "PaymentActivity.java",
        """
        import io.temporal.activity.ActivityInterface;
        import io.temporal.activity.ActivityMethod;

        @ActivityInterface
        public interface PaymentActivity {
          @ActivityMethod
          void processPaymentActivity(String accountId);
        }
        """);

    List<CompilationUnit> units = parse();
    Predicate<MethodDeclaration> boundary = ActivityAwareness.activityBoundary(units);
    MethodDeclaration interfaceMethod = methodNamed(units, "PaymentActivity", "processPaymentActivity");

    assertThat(boundary.test(interfaceMethod)).isTrue();
  }

  @Test
  void doesNotTreatAnUnrelatedMethodAsABoundary() throws IOException {
    writeJavaFile(
        "PaymentActivity.java",
        """
        import io.temporal.activity.ActivityInterface;
        import io.temporal.activity.ActivityMethod;

        @ActivityInterface
        public interface PaymentActivity {
          @ActivityMethod
          void processPaymentActivity(String accountId);
        }
        """);
    writeJavaFile(
        "PaymentService.java",
        """
        public class PaymentService {
          public void unrelatedMethod() {
          }
        }
        """);

    List<CompilationUnit> units = parse();
    Predicate<MethodDeclaration> boundary = ActivityAwareness.activityBoundary(units);
    MethodDeclaration unrelatedMethod = methodNamed(units, "PaymentService", "unrelatedMethod");

    assertThat(boundary.test(unrelatedMethod)).isFalse();
  }

  @Test
  void doesNotTreatAPlainUnannotatedInterfaceImplementationAsABoundary() throws IOException {
    writeJavaFile(
        "NotAnActivity.java",
        """
        public interface NotAnActivity {
          void doStuff();
        }
        """);
    writeJavaFile(
        "NotAnActivityImpl.java",
        """
        public class NotAnActivityImpl implements NotAnActivity {
          @Override
          public void doStuff() {
          }
        }
        """);

    List<CompilationUnit> units = parse();
    Predicate<MethodDeclaration> boundary = ActivityAwareness.activityBoundary(units);
    MethodDeclaration implMethod = methodNamed(units, "NotAnActivityImpl", "doStuff");

    assertThat(boundary.test(implMethod)).isFalse();
  }
}
