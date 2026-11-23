package tz.co.pharmacy.modules.access.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.platform.kernel.Refusal;

class PasswordRuleTest {

  @Test
  @DisplayName("common, repeated, sequential and own-name passwords are refused (NIST 800-63B)")
  void refusesGuessable() {
    for (String weak :
        new String[] {"Password123", "12345678", "87654321", "abcdefgh", "zzzzzzzz", "tanzania1"}) {
      assertThatThrownBy(() -> Passwords.acceptable(weak, "password"))
          .as(weak)
          .isInstanceOf(Refusal.class)
          .hasMessageContaining("too easy");
    }
    assertThatThrownBy(() -> Passwords.acceptable("Juma.2026!", "password", "juma@dawa"))
        .isInstanceOf(Refusal.class);
  }

  @Test
  @DisplayName("ordinary passwords pass, counted in characters, with no composition rules")
  void acceptsOrdinary() {
    for (String fine :
        new String[] {"Admin#2026x", "korosho na chai", "Kariakoo#2026", "mwembe-mrefu-77"}) {
      assertThatCode(() -> Passwords.acceptable(fine, "password", "neema@example.com"))
          .as(fine)
          .doesNotThrowAnyException();
    }
    // Eight characters, four of them outside the Basic Multilingual Plane: still eight.
    assertThatCode(() -> Passwords.acceptable("ab😀😀😀😀cd", "password"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> Passwords.acceptable("short", "password")).isInstanceOf(Refusal.class);
  }
}
