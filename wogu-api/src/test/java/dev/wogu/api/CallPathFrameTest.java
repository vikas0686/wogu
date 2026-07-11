package dev.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CallPathFrameTest {

  @Test
  void exposesDisplayNameFileAndLine() {
    CallPathFrame frame = new CallPathFrame("OrderService.createOrder()", Path.of("OrderService.java"), 12);

    assertThat(frame.displayName()).isEqualTo("OrderService.createOrder()");
    assertThat(frame.file()).isEqualTo(Path.of("OrderService.java"));
    assertThat(frame.line()).isEqualTo(12);
  }

  @Test
  void rejectsLineBelowOne() {
    assertThatThrownBy(() -> new CallPathFrame("x", Path.of("Foo.java"), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toStringIncludesLocation() {
    CallPathFrame frame = new CallPathFrame("UUID.randomUUID()", Path.of("Foo.java"), 5);

    assertThat(frame.toString()).contains("UUID.randomUUID()").contains("Foo.java").contains("5");
  }
}
