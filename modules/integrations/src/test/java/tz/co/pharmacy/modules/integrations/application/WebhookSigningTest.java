package tz.co.pharmacy.modules.integrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.integrations.domain.Webhook;

/** Standard Webhooks signing and secret rotation (INT-13). */
class WebhookSigningTest {

  @Test
  @DisplayName("the signature matches the Standard Webhooks specification's example")
  void specificationExample() {
    assertThat(
            WebhookSender.standardSignature(
                "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw",
                "msg_p5jXN8AQM9LWM0D4loKWxJek",
                1614265330L,
                "{\"test\": 2432232314}"))
        .isEqualTo("g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
  }

  @Test
  @DisplayName("during a rotation the header carries one v1 signature per secret, space-separated")
  void severalSignatures() {
    String fresh = WebhookSecrets.generate();
    String old = WebhookSecrets.generate();
    String header = WebhookSender.signatures(List.of(fresh, old), "evt-1", 1700000000L, "{}");
    assertThat(header.split(" "))
        .containsExactly(
            "v1," + WebhookSender.standardSignature(fresh, "evt-1", 1700000000L, "{}"),
            "v1," + WebhookSender.standardSignature(old, "evt-1", 1700000000L, "{}"));
  }

  @Test
  @DisplayName(
      "a new secret is whsec_ and base64 of 32 random bytes; an old hex secret still signs")
  void secretFormat() {
    String secret = WebhookSecrets.generate();
    assertThat(secret).startsWith("whsec_");
    assertThat(Base64.getDecoder().decode(secret.substring(6))).hasSize(32);
    assertThat(WebhookSecrets.generate()).isNotEqualTo(secret);
    // Saved before this release: whsec_ and 64 hex digits, which a Standard Webhooks library reads
    // as base64 too (48 bytes), so receivers verify with the secret they already have.
    String legacy = "whsec_" + "0123456789abcdef".repeat(4);
    assertThat(WebhookSender.standardSignature(legacy, "id", 1L, "body")).isNotBlank();
  }

  @Test
  @DisplayName("the previous secret signs for the 24 hours after a rotation only")
  void previousSecretWindow() {
    Webhook webhook =
        new Webhook(
            "Hook", "https://hooks.example.com/x", "sealed-1", List.of("sale.completed"), true);
    Instant at = Instant.parse("2026-11-23T10:00:00Z");
    assertThat(webhook.previousSigns(at)).isFalse();
    webhook.rotate("sealed-2", "sealed-1", at.plus(WebhooksImpl.ROTATION_OVERLAP));
    assertThat(webhook.getSecret()).isEqualTo("sealed-2");
    assertThat(webhook.getPreviousSecret()).isEqualTo("sealed-1");
    assertThat(webhook.previousSigns(at.plusSeconds(23 * 3600))).isTrue();
    assertThat(webhook.previousSigns(at.plusSeconds(24 * 3600))).isFalse();
  }
}
