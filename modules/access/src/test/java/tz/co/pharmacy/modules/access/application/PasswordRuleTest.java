package tz.co.pharmacy.modules.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

class PasswordRuleTest {

  @Test
  @DisplayName("common, repeated, sequential and own-name passwords are refused (NIST 800-63B)")
  void refusesGuessable() {
    for (String weak :
        new String[] {"Password123", "12345678", "87654321", "abcdefgh", "zzzzzzzz", "tanzania1"}) {
      assertThatThrownBy(() -> Passwords.acceptable(weak, "password", null, true))
          .as(weak)
          .isInstanceOf(Refusal.class)
          .hasMessageContaining("too easy");
    }
    assertThatThrownBy(() -> Passwords.acceptable("Juma.2026!-strong", "password", "juma@dawa"))
        .isInstanceOf(Refusal.class)
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("common_password");
    // The breach list still applies to a long one made of a common word repeated.
    assertThatThrownBy(() -> Passwords.acceptable("aaaaaaaaaaaaaaaa", "password"))
        .isInstanceOf(Refusal.class)
        .hasMessageContaining("too easy");
  }

  @Test
  @DisplayName("ordinary passwords pass, counted in characters, with no composition rules")
  void acceptsOrdinary() {
    for (String fine :
        new String[] {
          "Admin#2026x-strong", "korosho na chai tamu", "Kariakoo#2026-strong", "mwembe-mrefu-77-x"
        }) {
      assertThatCode(() -> Passwords.acceptable(fine, "password", "neema@example.com"))
          .as(fine)
          .doesNotThrowAnyException();
    }
    // Eight characters, four of them outside the Basic Multilingual Plane: still eight, which an
    // account with a second factor may use.
    assertThatCode(() -> Passwords.acceptable("ab😀😀😀😀cd", "password", null, true))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> Passwords.acceptable("short", "password", null, true))
        .isInstanceOf(Refusal.class);
  }

  @Test
  @DisplayName("STD-21: 15 characters when the password stands alone, 8 with a second factor")
  void lengthFollowsTheFactors() {
    Refusal alone =
        (Refusal)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> Passwords.acceptable("Admin#2026x", "newPassword"));
    assertThat(alone.reason()).isEqualTo("password_too_short");
    assertThat(alone.extras()).containsEntry("minLength", 15).containsEntry("twoFactor", false);
    assertThat(alone.field()).isEqualTo("newPassword");
    // Fourteen characters are one short; fifteen pass.
    assertThatThrownBy(() -> Passwords.acceptable("mwembe-mrefu-7", "password"))
        .isInstanceOf(Refusal.class);
    assertThatCode(() -> Passwords.acceptable("mwembe-mrefu-77", "password"))
        .doesNotThrowAnyException();
    // With an authenticator app or a passkey, eight are enough.
    assertThatCode(() -> Passwords.acceptable("Admin#2026x", "password", null, true))
        .doesNotThrowAnyException();
    Refusal withFactor =
        (Refusal)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> Passwords.acceptable("Ab#2026", "password", null, true));
    assertThat(withFactor.extras()).containsEntry("minLength", 8).containsEntry("twoFactor", true);
    // Above 128 stays weak_password.
    assertThatThrownBy(() -> Passwords.acceptable("x7".repeat(65), "password"))
        .isInstanceOf(Refusal.class)
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("weak_password");
    assertThat(Passwords.longEnoughAlone("mwembe-mrefu-77")).isTrue();
    assertThat(Passwords.longEnoughAlone("mwembe-mrefu-7")).isFalse();
    assertThat(Passwords.longEnoughAlone(null)).isFalse();
  }

  @Test
  @DisplayName("operator-configured passwords (root bootstrap, demo seed) need 8; others 15")
  void operatorPasswords() {
    ExecutionContext.callAs(
        ExecutionContext.Frame.service("bootstrap", null, null),
        () -> {
          assertThatCode(() -> Passwords.acceptable("Root#Test2026", "password"))
              .doesNotThrowAnyException();
          return null;
        });
    ExecutionContext.callAs(
        ExecutionContext.Frame.service("payments-job", null, null),
        () -> {
          assertThatThrownBy(() -> Passwords.acceptable("Root#Test2026", "password"))
              .isInstanceOf(Refusal.class);
          return null;
        });
  }

  @Test
  @DisplayName("temporary passwords have 16 characters, enough to stand alone")
  void temporaryPasswordsAreLongEnough() {
    String temporary = TemporaryPasswords.next();
    assertThat(temporary).matches("[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}");
    assertThatCode(() -> Passwords.acceptable(temporary, "password")).doesNotThrowAnyException();
  }
}
