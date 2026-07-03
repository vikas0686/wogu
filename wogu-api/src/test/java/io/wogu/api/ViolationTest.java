package io.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ViolationTest {

  @Test
  void buildsWithAllFieldsPopulated() {
    Violation violation =
        Violation.builder()
            .validatorId("uuid-random-in-workflow")
            .severity(Severity.ERROR)
            .file(Path.of("src/main/java/Foo.java"))
            .className("com.example.Foo")
            .line(42)
            .message("UUID.randomUUID() is non-deterministic")
            .suggestedFix("Use Workflow.randomUUID() instead")
            .build();

    assertThat(violation.validatorId()).isEqualTo("uuid-random-in-workflow");
    assertThat(violation.severity()).isEqualTo(Severity.ERROR);
    assertThat(violation.file()).isEqualTo(Path.of("src/main/java/Foo.java"));
    assertThat(violation.className()).isEqualTo("com.example.Foo");
    assertThat(violation.line()).isEqualTo(42);
    assertThat(violation.message()).contains("non-deterministic");
    assertThat(violation.suggestedFix()).contains("Workflow.randomUUID()");
  }

  @Test
  void rejectsLineNumberBelowOne() {
    Violation.Builder builder =
        Violation.builder()
            .validatorId("v")
            .severity(Severity.ERROR)
            .file(Path.of("Foo.java"))
            .className("Foo")
            .line(0)
            .message("m")
            .suggestedFix("f");

    assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingRequiredField() {
    Violation.Builder builder =
        Violation.builder()
            .severity(Severity.ERROR)
            .file(Path.of("Foo.java"))
            .className("Foo")
            .line(1)
            .message("m")
            .suggestedFix("f");

    assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class);
  }

  @Test
  void equalsAndHashCodeAreValueBased() {
    Violation a =
        Violation.builder()
            .validatorId("v")
            .severity(Severity.WARNING)
            .file(Path.of("Foo.java"))
            .className("Foo")
            .line(10)
            .message("m")
            .suggestedFix("f")
            .build();
    Violation b =
        Violation.builder()
            .validatorId("v")
            .severity(Severity.WARNING)
            .file(Path.of("Foo.java"))
            .className("Foo")
            .line(10)
            .message("m")
            .suggestedFix("f")
            .build();

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  void toStringIncludesLocationAndMessage() {
    Violation violation =
        Violation.builder()
            .validatorId("v")
            .severity(Severity.ERROR)
            .file(Path.of("Foo.java"))
            .className("com.example.Foo")
            .line(7)
            .message("boom")
            .suggestedFix("fix it")
            .build();

    assertThat(violation.toString()).contains("Foo.java").contains("7").contains("boom");
  }
}
