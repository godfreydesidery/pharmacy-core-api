package tz.co.pharmacy.modules.notifications.providers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogOutputTest {

  @Test
  @DisplayName("addresses are masked when bodies are withheld")
  void masksAddresses() {
    assertThat(LogOutput.mask("+255712345678")).isEqualTo("+25…78");
    assertThat(LogOutput.mask("juma@example.com")).isEqualTo("ju…@example.com");
    assertThat(LogOutput.mask("a@b")).isEqualTo("…");
    assertThat(LogOutput.mask(null)).isEqualTo("…");
  }
}
