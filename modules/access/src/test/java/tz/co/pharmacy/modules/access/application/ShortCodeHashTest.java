package tz.co.pharmacy.modules.access.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.platform.crypto.Keyring;
import tz.co.pharmacy.platform.crypto.KeyringSettings;
import tz.co.pharmacy.platform.crypto.Purpose;

/**
 * Recovery and SMS codes are stored as keyed hashes (adversarial review AUTH-16): a copy of the
 * table alone does not let anyone try every code, and codes stored before as SHA-256 still match.
 */
class ShortCodeHashTest {

  private static final String LEGACY = "short-code-test-legacy-key-0123456789";
  private static final String NEW = "short-code-test-new-key-0123456789abc";

  private final SecretBox secrets = box(Map.of(), Map.of());

  @Test
  @DisplayName("a code is stored as an HMAC under a named key, not as its bare SHA-256")
  void keyedNotBare() {
    String stored = RecoveryCodes.hash(secrets, "k7mq-2xhz");
    assertThat(stored).startsWith("h1:legacy:");
    assertThat(stored).isNotEqualTo(RecoveryCodes.legacyHash("k7mq-2xhz"));
    assertThat(stored).doesNotContain(Tokens.hash("recovery:k7mq2xhz"));
    // Another pepper, another hash: the database alone does not tell what the code was.
    SecretBox other =
        new SecretBox(
            new Keyring(
                new KeyringSettings("another-legacy-key-0123456789abcdef", Map.of(), Map.of())));
    assertThat(RecoveryCodes.hash(other, "k7mq-2xhz")).isNotEqualTo(stored);
    assertThat(secrets.codeMatches(stored, "recovery:k7mq2xhz", RecoveryCodes.legacyHash("x")))
        .isTrue();
    assertThat(secrets.codeMatches(stored, "recovery:k7mq2xha", RecoveryCodes.legacyHash("x")))
        .isFalse();
  }

  @Test
  @DisplayName("codes stored as SHA-256 before, or under an earlier key, keep matching")
  void earlierHashesKeepWorking() {
    String legacy = RecoveryCodes.legacyHash("k7mq-2xhz");
    assertThat(RecoveryCodes.hashes(secrets, "K7MQ2XHZ")).contains(legacy);
    assertThat(secrets.codeMatches(legacy, "recovery:k7mq2xhz", legacy)).isTrue();

    // The codes pepper moves to a new key: hashes under the old one are still looked up.
    String before = RecoveryCodes.hash(secrets, "k7mq-2xhz");
    SecretBox rotated = box(Map.of("k2027a", NEW), Map.of(Purpose.CODES, "k2027a"));
    assertThat(RecoveryCodes.hash(rotated, "k7mq-2xhz")).startsWith("h1:k2027a:");
    assertThat(RecoveryCodes.hashes(rotated, "k7mq-2xhz")).contains(before);
    assertThat(rotated.codeMatches(before, "recovery:k7mq2xhz", legacy)).isTrue();
  }

  @Test
  @DisplayName("unset, the codes pepper is the key the TOTP seeds are sealed with")
  void codesFollowTotpUntilSet() {
    SecretBox totp = box(Map.of("k2027a", NEW), Map.of(Purpose.TOTP, "k2027a"));
    assertThat(RecoveryCodes.hash(totp, "k7mq-2xhz")).startsWith("h1:k2027a:");
  }

  private static SecretBox box(Map<String, String> keys, Map<Purpose, String> active) {
    return new SecretBox(new Keyring(new KeyringSettings(LEGACY, keys, active)));
  }
}
