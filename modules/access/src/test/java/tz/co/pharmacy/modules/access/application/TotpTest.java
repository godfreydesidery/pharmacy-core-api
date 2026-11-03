package tz.co.pharmacy.modules.access.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {

  /** The SHA-1 seed of RFC 6238, appendix B. */
  private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  @ParameterizedTest(name = "T = {0} s gives {1}")
  @DisplayName("matches the RFC 6238 appendix B test vectors (SHA-1, 8 digits)")
  @CsvSource({
    "59, 94287082",
    "1111111109, 07081804",
    "1111111111, 14050471",
    "1234567890, 89005924",
    "2000000000, 69279037",
    "20000000000, 65353130"
  })
  void rfc6238Vectors(long seconds, String expected) {
    assertThat(Totp.code(RFC_SEED, Totp.step(Instant.ofEpochSecond(seconds)), 8))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("accepts the current, previous and next step, and nothing further")
  void driftWindow() {
    Instant now = Instant.ofEpochSecond(1_700_000_000L);
    long step = Totp.step(now);
    assertThat(Totp.match(RFC_SEED, Totp.code(RFC_SEED, step, 6), now)).isEqualTo(step);
    assertThat(Totp.match(RFC_SEED, Totp.code(RFC_SEED, step - 1, 6), now)).isEqualTo(step - 1);
    assertThat(Totp.match(RFC_SEED, Totp.code(RFC_SEED, step + 1, 6), now)).isEqualTo(step + 1);
    assertThat(Totp.match(RFC_SEED, Totp.code(RFC_SEED, step - 2, 6), now)).isEqualTo(-1);
    assertThat(Totp.match(RFC_SEED, "abcdef", now)).isEqualTo(-1);
    assertThat(Totp.match(RFC_SEED, null, now)).isEqualTo(-1);
  }

  @Test
  @DisplayName("base32 round-trips and matches RFC 4648")
  void base32() {
    assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    byte[] secret = Totp.newSecret();
    assertThat(Totp.fromBase32(Totp.base32(secret))).isEqualTo(secret);
  }

  @Test
  @DisplayName("builds an otpauth URI authenticator apps accept")
  void otpauthUri() {
    assertThat(Totp.otpauthUri("Pharmacy Core", "amina@afya.co.tz", "ABC"))
        .isEqualTo(
            "otpauth://totp/Pharmacy%20Core:amina%40afya.co.tz?secret=ABC&issuer=Pharmacy%20Core"
                + "&algorithm=SHA1&digits=6&period=30");
  }
}
