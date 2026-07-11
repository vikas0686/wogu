package dev.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViolationTest {

  private static Rule testRule() {
    return Rule.builder()
        .id("WG001")
        .title("UUID.randomUUID() inside Workflow")
        .category(RuleCategory.DETERMINISM)
        .severity(Severity.ERROR)
        .engine("Temporal Java SDK")
        .sinceVersion("0.1.0")
        .documentationReference("docs/rules/WG001.md")
        .build();
  }

  @Test
  void buildsWithAllFieldsPopulated() {
    Violation violation =
        Violation.builder()
            .rule(testRule())
            .file(Path.of("src/main/java/Foo.java"))
            .className("com.example.Foo")
            .line(42)
            .message("UUID.randomUUID() is non-deterministic")
            .suggestedFix("Use Workflow.randomUUID() instead")
            .build();

    assertThat(violation.rule()).isEqualTo(testRule());
    assertThat(violation.severity()).isEqualTo(Severity.ERROR);
    assertThat(violation.file()).isEqualTo(Path.of("src/main/java/Foo.java"));
    assertThat(violation.className()).isEqualTo("com.example.Foo");
    assertThat(violation.line()).isEqualTo(42);
    assertThat(violation.message()).contains("non-deterministic");
    assertThat(violation.suggestedFix()).contains("Workflow.randomUUID()");
    assertThat(violation.callPath()).isEmpty();
  }

  @Test
  void carriesAnOptionalCallPath() {
    List<CallPathFrame> path =
        List.of(
            new CallPathFrame("PaymentWorkflowImpl.processPayment()", Path.of("Foo.java"), 10),
            new CallPathFrame("UUID.randomUUID()", Path.of("Foo.java"), 12));

    Violation violation =
        Violation.builder()
            .rule(testRule())
            .file(Path.of("Foo.java"))
            .className("Foo")
            .line(12)
            .message("m")
            .suggestedFix("f")
            .callPath(path)
            .build();

    assertThat(violation.callPath()).containsExactlyElementsOf(path);
  }

  @Test
  void rejectsLineNumberBelowOne() {
    Violation.Builder builder =
        Violation.builder().rule(testRule()).file(Path.of("Foo.java")).className("Foo").line(0).message("m").suggestedFix("f");

    assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingRequiredField() {
    Violation.Builder builder =
        Violation.builder().file(Path.of("Foo.java")).className("Foo").line(1).message("m").suggestedFix("f");

    assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class);
  }

  @Test
  void equalsAndHashCodeAreValueBased() {
    Violation a =
        Violation.builder().rule(testRule()).file(Path.of("Foo.java")).className("Foo").line(10).message("m").suggestedFix("f").build();
    Violation b =
        Violation.builder().rule(testRule()).file(Path.of("Foo.java")).className("Foo").line(10).message("m").suggestedFix("f").build();

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  void toStringIncludesLocationAndMessage() {
    Violation violation =
        Violation.builder()
            .rule(testRule())
            .file(Path.of("Foo.java"))
            .className("com.example.Foo")
            .line(7)
            .message("boom")
            .suggestedFix("fix it")
            .build();

    assertThat(violation.toString()).contains("Foo.java").contains("7").contains("boom");
  }
}
