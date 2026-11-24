package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * STD-21 (NIST SP 800-63B-4, owner's decision of 24 Nov 2026): a password needs 15 characters when
 * it is the account's only factor and 8 with two-factor; an account never ends up with a shorter
 * password as its only factor.
 */
class PasswordLengthTest extends IntegrationTest {

  @Test
  @DisplayName("15 without two-factor, 8 with it; two-factor stays until the password is long")
  void lengthFollowsTheFactors() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "PL" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String register =
        """
        {"code":"%s","name":"Lengths %s","legalName":"Lengths %s Ltd","country":"TZ",
         "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
         "headOffice":{"code":"HQ","name":"Head Office"},
         "admin":{"name":"Admin","email":"%s","password":"%s"}}
        """;
    // A new account has no second factor: 11 characters are too few.
    Response refused =
        root.post(
                "/api/v1/platform/tenants",
                register.formatted(code, code, code, email, "Admin#2026x"))
            .expect(422);
    assertThat(refused.string("$.reason")).isEqualTo("password_too_short");
    assertThat(refused.read("$.minLength", Integer.class)).isEqualTo(15);
    assertThat(refused.read("$.twoFactor", Boolean.class)).isFalse();
    root.post(
            "/api/v1/platform/tenants",
            register.formatted(code, code, code, email, "Admin#2026x-strong"))
        .expect(201);

    Api me = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong"));
    Response security = me.get("/api/v1/auth/security").expect(200);
    assertThat(security.read("$.data.minPasswordLength", Integer.class)).isEqualTo(15);
    assertThat(security.read("$.data.passwordLongEnoughAlone", Boolean.class)).isTrue();
    assertThat(change(me, "Admin#2026x-strong", "Short#2026x1").expect(422).string("$.reason"))
        .isEqualTo("password_too_short");

    // With an authenticator app, 8 characters are enough.
    String secret = me.post("/api/v1/auth/2fa/setup", "{}").expect(200).string("$.data.secret");
    Instant now = Instant.now();
    me.post("/api/v1/auth/2fa/enable", "{\"code\":\"" + totp(secret, now) + "\"}").expect(200);
    assertThat(
            me.get("/api/v1/auth/security")
                .expect(200)
                .read("$.data.minPasswordLength", Integer.class))
        .isEqualTo(8);
    change(me, "Admin#2026x-strong", "Short#2026x1").expect(200);
    assertThat(
            me.get("/api/v1/auth/security")
                .expect(200)
                .read("$.data.passwordLongEnoughAlone", Boolean.class))
        .isFalse();

    // Turning two-factor off would leave a 12-character password alone: refused.
    Response kept =
        me.post(
                "/api/v1/auth/2fa/disable",
                "{\"code\":\"" + totp(secret, now.plusSeconds(30)) + "\"}")
            .expect(409);
    assertThat(kept.string("$.reason")).isEqualTo("password_too_short_without_two_factor");
    assertThat(kept.read("$.minLength", Integer.class)).isEqualTo(15);

    // A long password first, then two-factor may go.
    change(me, "Short#2026x1", "Longer#2026-pass").expect(200);
    me.post("/api/v1/auth/2fa/disable", "{\"code\":\"" + totp(secret, now.plusSeconds(30)) + "\"}")
        .expect(200);
    assertThat(
            me.get("/api/v1/auth/security")
                .expect(200)
                .read("$.data.minPasswordLength", Integer.class))
        .isEqualTo(15);
  }

  private static Response change(Api me, String current, String next) {
    return me.post(
        "/api/v1/auth/change-password",
        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next));
  }

  private static String totp(String base32Secret, Instant at) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(base32(base32Secret), "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(at.getEpochSecond() / 30).array());
      int offset = hash[hash.length - 1] & 0xf;
      int binary = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
      return String.format("%06d", binary % 1_000_000);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] base32(String value) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    String clean = value.replace("=", "").replace(" ", "").toUpperCase();
    ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
    int buffer = 0;
    int bits = 0;
    for (char c : clean.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.put((byte) (buffer >> (bits - 8)));
        bits -= 8;
      }
    }
    return out.array();
  }
}
