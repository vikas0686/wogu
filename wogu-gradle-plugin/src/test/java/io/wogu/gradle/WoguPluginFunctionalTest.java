package io.wogu.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests that apply {@link WoguPlugin} to a real (temporary) Gradle project via
 * Gradle TestKit and run the {@code woguValidate} task, the same way a real consumer's
 * build would.
 *
 * <p>The test project applies the {@code java} plugin but never runs {@code compileJava}
 * (only the {@code woguValidate} task is invoked directly), so its workflow sources can
 * reference Temporal annotations by name without an actual {@code temporal-sdk}
 * dependency: WoGu's detection is syntactic and never requires the code to compile.
 */
class WoguPluginFunctionalTest {

  @TempDir Path projectDir;

  private void writeBuildFile() throws IOException {
    Files.writeString(
        projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"functional-test-project\"\n");
    Files.writeString(
        projectDir.resolve("build.gradle.kts"),
        """
        plugins {
          java
          id("io.wogu.wogu-gradle-plugin")
        }
        """);
  }

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = projectDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private GradleRunner runner(String... arguments) {
    return GradleRunner.create().withPluginClasspath().withProjectDir(projectDir.toFile()).withArguments(arguments);
  }

  @Test
  void passesWhenNoWorkflowUsesUuidRandomUuid() throws IOException {
    writeBuildFile();
    writeJavaFile(
        "src/main/java/com/example/OrderWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface OrderWorkflow {
          void placeOrder();
        }
        """);
    writeJavaFile(
        "src/main/java/com/example/OrderWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class OrderWorkflowImpl implements OrderWorkflow {
          @Override
          public void placeOrder() {
            String id = Workflow.randomUUID().toString();
          }
        }
        """);

    BuildResult result = runner("woguValidate").build();

    assertThat(result.task(":woguValidate").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(result.getOutput()).contains("Running WoGu...").contains("PASSED");
    assertThat(projectDir.resolve("build/reports/wogu/index.html")).exists();
  }

  @Test
  void failsTheBuildWhenAWorkflowUsesUuidRandomUuid() throws IOException {
    writeBuildFile();
    writeJavaFile(
        "src/main/java/com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface PaymentWorkflow {
          void pay();
        }
        """);
    writeJavaFile(
        "src/main/java/com/example/PaymentWorkflowImpl.java",
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

    BuildResult result = runner("woguValidate").buildAndFail();

    assertThat(result.task(":woguValidate").getOutcome()).isEqualTo(TaskOutcome.FAILED);
    assertThat(result.getOutput()).contains("FAILED").contains("1 violation found").contains("Build failed.");
    Path report = projectDir.resolve("build/reports/wogu/index.html");
    assertThat(report).exists();
    assertThat(Files.readString(report)).contains("PaymentWorkflowImpl");
  }

  @Test
  void woguValidateRunsAsPartOfBuild() throws IOException {
    // Unlike the other tests, this one runs the real 'build' task, which also runs
    // compileJava. That requires an actual temporal-sdk dependency to resolve the
    // @WorkflowInterface import, so this test's project looks like a genuine consumer's
    // build rather than the minimal, offline-friendly projects used elsewhere in this class.
    Files.writeString(
        projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"functional-test-project\"\n");
    Files.writeString(
        projectDir.resolve("build.gradle.kts"),
        """
        plugins {
          java
          id("io.wogu.wogu-gradle-plugin")
        }

        repositories {
          mavenCentral()
        }

        dependencies {
          implementation("io.temporal:temporal-sdk:1.30.1")
        }
        """);
    writeJavaFile(
        "src/main/java/com/example/OrderWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;

        @WorkflowInterface
        public interface OrderWorkflow {
          void placeOrder();
        }
        """);
    writeJavaFile(
        "src/main/java/com/example/OrderWorkflowImpl.java",
        """
        package com.example;

        import io.temporal.workflow.Workflow;

        public class OrderWorkflowImpl implements OrderWorkflow {
          @Override
          public void placeOrder() {
            String id = Workflow.randomUUID().toString();
          }
        }
        """);

    BuildResult result = runner("build").build();

    assertThat(result.task(":woguValidate")).isNotNull();
    assertThat(result.task(":woguValidate").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
  }

  @Test
  void skipsValidationWhenConfigured() throws IOException {
    writeBuildFile();
    Files.writeString(
        projectDir.resolve("build.gradle.kts"),
        """
        plugins {
          java
          id("io.wogu.wogu-gradle-plugin")
        }

        wogu {
          skip = true
        }
        """);

    BuildResult result = runner("woguValidate").build();

    assertThat(result.task(":woguValidate").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(result.getOutput()).contains("WoGu validation skipped");
  }
}
