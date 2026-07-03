package io.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import io.wogu.api.Severity;
import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationResult;
import io.wogu.api.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UUIDRandomValidatorTest {

  @TempDir Path sourceRoot;

  private final UUIDRandomValidator validator = new UUIDRandomValidator();

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private ValidationContext context() {
    return new TestValidationContext("test-project", sourceRoot, List.of(sourceRoot));
  }

  @Test
  void flagsUuidRandomUuidInsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface PaymentWorkflow {
          void pay();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.util.UUID;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void pay() {
            String id = UUID.randomUUID().toString();
          }
        }
        """);

    ValidationResult result = validator.validate(context());

    assertThat(result.passed()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Violation violation = result.violations().get(0);
    assertThat(violation.validatorId()).isEqualTo(UUIDRandomValidator.ID);
    assertThat(violation.severity()).isEqualTo(Severity.ERROR);
    assertThat(violation.className()).isEqualTo("com.example.PaymentWorkflowImpl");
    assertThat(violation.line()).isEqualTo(8);
    assertThat(violation.message()).contains("non-deterministic");
    assertThat(violation.suggestedFix()).contains("Workflow.randomUUID()");
  }

  @Test
  void doesNotFlagTemporalsDeterministicWorkflowRandomUuid() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface PaymentWorkflow {
          void pay();
        }
        """);
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void pay() {
            String id = Workflow.randomUUID().toString();
          }
        }
        """);

    ValidationResult result = validator.validate(context());

    assertThat(result.passed()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void doesNotFlagUuidRandomUuidOutsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PlainIdGenerator.java",
        """
        package com.example;

        import java.util.UUID;

        public class PlainIdGenerator {
          public String next() {
            return UUID.randomUUID().toString();
          }
        }
        """);

    ValidationResult result = validator.validate(context());

    assertThat(result.passed()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void flagsFullyQualifiedInlineUsageWithoutAnImport() throws IOException {
    writeJavaFile(
        "com/example/InlineWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface InlineWorkflow {
          void run();
        }
        """);
    writeJavaFile(
        "com/example/InlineWorkflowImpl.java",
        """
        package com.example;

        public class InlineWorkflowImpl implements InlineWorkflow {
          @Override
          public void run() {
            String id = java.util.UUID.randomUUID().toString();
          }
        }
        """);

    ValidationResult result = validator.validate(context());

    assertThat(result.violations()).hasSize(1);
  }

  @Test
  void findsMultipleViolationsAcrossMultipleFiles() throws IOException {
    writeJavaFile(
        "com/example/OrderWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface OrderWorkflow {
          void run();
        }
        """);
    writeJavaFile(
        "com/example/OrderWorkflowImpl.java",
        """
        package com.example;

        import java.util.UUID;

        public class OrderWorkflowImpl implements OrderWorkflow {
          @Override
          public void run() {
            String a = UUID.randomUUID().toString();
            String b = UUID.randomUUID().toString();
          }
        }
        """);
    writeJavaFile(
        "com/example/ShippingWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface ShippingWorkflow {
          void run();
        }
        """);
    writeJavaFile(
        "com/example/ShippingWorkflowImpl.java",
        """
        package com.example;

        import java.util.UUID;

        public class ShippingWorkflowImpl implements ShippingWorkflow {
          @Override
          public void run() {
            String a = UUID.randomUUID().toString();
          }
        }
        """);

    ValidationResult result = validator.validate(context());

    assertThat(result.violations()).hasSize(3);
  }
}
