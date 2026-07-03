package io.wogu.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultValidationContextTest {

  @Test
  void exposesConfiguredValues() {
    DefaultValidationContext context =
        DefaultValidationContext.builder()
            .projectName("sample")
            .projectDirectory(Path.of("/tmp/sample"))
            .sourceRoots(List.of(Path.of("/tmp/sample/src/main/java")))
            .classpathElements(List.of(Path.of("/tmp/sample/target/classes")))
            .buildTool("Maven")
            .build();

    assertThat(context.projectName()).isEqualTo("sample");
    assertThat(context.projectDirectory()).isEqualTo(Path.of("/tmp/sample"));
    assertThat(context.sourceRoots()).containsExactly(Path.of("/tmp/sample/src/main/java"));
    assertThat(context.classpathElements()).containsExactly(Path.of("/tmp/sample/target/classes"));
    assertThat(context.buildTool()).isEqualTo("Maven");
  }

  @Test
  void defaultsSourceRootsAndClasspathToEmpty() {
    DefaultValidationContext context =
        DefaultValidationContext.builder()
            .projectName("sample")
            .projectDirectory(Path.of("."))
            .buildTool("Gradle")
            .build();

    assertThat(context.sourceRoots()).isEmpty();
    assertThat(context.classpathElements()).isEmpty();
  }

  @Test
  void requiresProjectName() {
    DefaultValidationContext.Builder builder =
        DefaultValidationContext.builder().projectDirectory(Path.of(".")).buildTool("Maven");

    assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class);
  }

  @Test
  void requiresBuildTool() {
    DefaultValidationContext.Builder builder =
        DefaultValidationContext.builder().projectName("sample").projectDirectory(Path.of("."));

    assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class);
  }
}
