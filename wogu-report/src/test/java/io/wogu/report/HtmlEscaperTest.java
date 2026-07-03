package io.wogu.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlEscaperTest {

  @Test
  void escapesAllReservedHtmlCharacters() {
    assertThat(HtmlEscaper.escape("<script>alert('x')&\"y\"</script>"))
        .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&amp;&quot;y&quot;&lt;/script&gt;");
  }

  @Test
  void leavesPlainTextUnchanged() {
    assertThat(HtmlEscaper.escape("com.example.PaymentWorkflowImpl")).isEqualTo("com.example.PaymentWorkflowImpl");
  }
}
