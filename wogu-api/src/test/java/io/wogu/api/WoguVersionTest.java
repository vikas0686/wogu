package io.wogu.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WoguVersionTest {

  @Test
  void fallsBackToDevelopmentWhenNoManifestIsPresent() {
    // Running from test classes (not a packaged jar), so there is no
    // Implementation-Version manifest entry to read.
    assertThat(WoguVersion.current()).isEqualTo("development");
  }
}
