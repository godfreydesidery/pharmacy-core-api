package tz.co.pharmacy.modules.access.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.access.domain.User;

class LockoutTest {

  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final Duration LOCK = Duration.ofMinutes(15);
  private static final Instant T0 = Instant.parse("2026-11-03T08:00:00Z");

  @Test
  @DisplayName("an account locks on the fifth failure within 15 minutes, for 15 minutes")
  void accountLocks() {
    User user = new User("amina@afya.co.tz", "Amina", "hash", false);
    for (int i = 0; i < 4; i++) {
      assertThat(user.failedSignIn(T0.plusSeconds(i * 60L), 5, WINDOW, LOCK)).isFalse();
    }
    assertThat(user.lockedAt(T0.plusSeconds(240))).isFalse();
    assertThat(user.failedSignIn(T0.plusSeconds(300), 5, WINDOW, LOCK)).isTrue();
    assertThat(user.lockedAt(T0.plusSeconds(301))).isTrue();
    assertThat(user.lockedAt(T0.plusSeconds(300).plus(LOCK).plusSeconds(1))).isFalse();
  }

  @Test
  @DisplayName("failures older than the window do not count")
  void windowSlides() {
    User user = new User("amina@afya.co.tz", "Amina", "hash", false);
    for (int i = 0; i < 4; i++) {
      user.failedSignIn(T0.plusSeconds(i), 5, WINDOW, LOCK);
    }
    assertThat(user.failedSignIn(T0.plus(WINDOW).plusSeconds(10), 5, WINDOW, LOCK)).isFalse();
    assertThat(user.lockedAt(T0.plus(WINDOW).plusSeconds(11))).isFalse();
  }

  @Test
  @DisplayName("a successful sign-in clears the failures")
  void successClears() {
    User user = new User("amina@afya.co.tz", "Amina", "hash", false);
    for (int i = 0; i < 4; i++) {
      user.failedSignIn(T0.plusSeconds(i), 5, WINDOW, LOCK);
    }
    user.signedIn(T0.plusSeconds(10));
    assertThat(user.failedSignIn(T0.plusSeconds(11), 5, WINDOW, LOCK)).isFalse();
  }

  @Test
  @DisplayName("recovery codes are 10, distinct, and match whatever their case or dashes")
  void recoveryCodes() {
    var codes = RecoveryCodes.generate();
    assertThat(codes).hasSize(10).doesNotHaveDuplicates();
    String code = codes.getFirst();
    assertThat(RecoveryCodes.looksLikeRecoveryCode(code)).isTrue();
    assertThat(RecoveryCodes.hash(code.toUpperCase().replace("-", "")))
        .isEqualTo(RecoveryCodes.hash(code));
    assertThat(RecoveryCodes.looksLikeRecoveryCode("123456")).isFalse();
  }
}
