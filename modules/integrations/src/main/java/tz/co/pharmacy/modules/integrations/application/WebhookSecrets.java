package tz.co.pharmacy.modules.integrations.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.domain.Webhook;
import tz.co.pharmacy.modules.integrations.ports.outbound.SecretSealing;
import tz.co.pharmacy.platform.crypto.Purpose;
import tz.co.pharmacy.platform.crypto.SealedColumn;

/**
 * Webhook signing secrets: made in the Standard Webhooks format ({@code whsec_} + base64 of 32
 * random bytes), sealed at rest with the platform key, and opened only to sign.
 *
 * <p>Webhooks saved before secrets were sealed still hold {@code whsec_<hex>} in plain text. They
 * are sealed lazily, the first time the webhook is used or the minute job passes by ({@link
 * #sealInPlace}), or by the key re-seal: a SQL migration cannot seal, since the key is not in the
 * database.
 */
@Component
class WebhookSecrets {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretSealing sealing;

  WebhookSecrets(SecretSealing sealing) {
    this.sealing = sealing;
  }

  /** A new secret, in plain text: shown once to the admin, then only kept sealed. */
  static String generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Webhook.PLAIN_PREFIX + Base64.getEncoder().encodeToString(bytes);
  }

  String seal(String plain) {
    return sealing.seal(plain);
  }

  /** The plain secret of a stored value; a value saved before sealing is plain already. */
  String open(String stored) {
    if (stored == null) {
      return null;
    }
    return stored.startsWith(Webhook.PLAIN_PREFIX) ? stored : sealing.open(stored);
  }

  /** Seals a webhook's plain secrets in place (the caller's transaction saves it). */
  void sealInPlace(Webhook webhook) {
    if (!webhook.secretInPlainText()) {
      return;
    }
    webhook.sealed(
        sealStored(webhook.getSecret()),
        webhook.getPreviousSecret() == null ? null : sealStored(webhook.getPreviousSecret()));
  }

  private String sealStored(String stored) {
    return stored.startsWith(Webhook.PLAIN_PREFIX) ? sealing.seal(stored) : stored;
  }

  /**
   * The secrets that sign a delivery at {@code now}, newest first: the current one, and the
   * previous one during the day after a rotation. Throws {@link IllegalStateException} when the
   * current one was sealed under another key.
   */
  List<String> signing(Webhook webhook, Instant now) {
    List<String> secrets = new ArrayList<>();
    secrets.add(open(webhook.getSecret()));
    if (webhook.previousSigns(now)) {
      try {
        secrets.add(open(webhook.getPreviousSecret()));
      } catch (IllegalStateException unreadable) {
        // The new secret still signs; the old one is simply no longer offered.
      }
    }
    return secrets;
  }

  /** The integrations module's sealed columns, for the key re-seal and its status. */
  @Configuration(proxyBeanMethods = false)
  static class SealedColumns {

    @Bean
    SealedColumn sealedWebhookSecrets() {
      return new SealedColumn(
          "integrations.webhooks", "secret", Purpose.WEBHOOKS, true, Webhook.PLAIN_PREFIX);
    }

    @Bean
    SealedColumn sealedPreviousWebhookSecrets() {
      return new SealedColumn(
          "integrations.webhooks", "previous_secret", Purpose.WEBHOOKS, true, Webhook.PLAIN_PREFIX);
    }
  }
}
