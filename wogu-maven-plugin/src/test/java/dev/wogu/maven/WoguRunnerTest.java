package dev.wogu.maven;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wogu.api.ValidationContext;
import dev.wogu.core.DefaultValidationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WoguRunnerTest {

  @TempDir Path sourceRoot;
  @TempDir Path reportDirectory;

  private final WoguRunner runner = new WoguRunner(WoguRunnerTest.class.getClassLoader());

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private ValidationContext context() {
    return DefaultValidationContext.builder()
        .projectName("sample-project")
        .projectDirectory(sourceRoot)
        .sourceRoots(List.of(sourceRoot))
        .buildTool("Maven")
        .build();
  }

  @Test
  void discoversTheTemporalUuidValidatorAndReportsAViolation() throws IOException {
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

    WoguRunner.Result result = runner.run(context(), reportDirectory);

    assertThat(result.summary().hasBuildFailures()).isTrue();
    assertThat(result.summary().allViolations()).hasSize(1);
    assertThat(result.reportPath()).exists();
    assertThat(Files.readString(result.reportPath())).contains("PaymentWorkflowImpl");
  }

  @Test
  void passesWhenNoWorkflowUsesUuidRandomUuid() throws IOException {
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

    WoguRunner.Result result = runner.run(context(), reportDirectory);

    assertThat(result.summary().hasBuildFailures()).isFalse();
    assertThat(result.summary().allViolations()).isEmpty();
    assertThat(result.reportPath()).exists();
  }
}
