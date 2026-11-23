package tz.co.pharmacy.modules.integrations.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.domain.Webhook;

/**
 * Sends one CloudEvent signed as <a href="https://www.standardwebhooks.com/">Standard Webhooks</a>
 * define: {@code POST} with {@code Content-Type: application/cloudevents+json} and
 *
 * <ul>
 *   <li>{@code webhook-id}: the event's id, the same on every retry (the receiver's idempotency
 *       key);
 *   <li>{@code webhook-timestamp}: the attempt's time in Unix seconds, so a receiver can refuse a
 *       replay (the spec suggests a 5 minute tolerance);
 *   <li>{@code webhook-signature}: {@code v1,<base64 HMAC-SHA256 of "id.timestamp.body">} keyed
 *       with the base64-decoded part of the secret after {@code whsec_}; during the day after a
 *       rotation, one signature per secret, space-separated.
 * </ul>
 *
 * <p>For one release the old {@code X-Pharmacy-Signature: sha256=<hex HMAC-SHA256 of the body>}
 * (keyed with the whole secret's UTF-8 bytes, the newest secret) is sent too, unless {@code
 * pharmacy.integrations.webhooks.legacy-signature} is false. Up to three attempts, 1 s and 2 s
 * apart; any 2xx is a delivery.
 */
@Component
class WebhookSender {

  static final int MAX_ATTEMPTS = 3;
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  private final WebhookTargets targets;
  private final Clock clock;
  private final boolean legacySignature;

  WebhookSender(
      WebhookTargets targets,
      Clock clock,
      @Value("${pharmacy.integrations.webhooks.legacy-signature:true}") boolean legacySignature) {
    this.targets = targets;
    this.clock = clock;
    this.legacySignature = legacySignature;
  }

  record Outcome(boolean delivered, int attempts, Integer responseStatus, String error) {}

  /**
   * Sends {@code body} to {@code url}, signed with each of {@code secrets} (plain, newest first).
   */
  Outcome send(String url, List<String> secrets, String messageId, String eventType, String body) {
    Integer status = null;
    String error = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      // Checked again before every attempt: the host may now resolve to a private address.
      WebhookTargets.Verdict verdict = targets.check(url);
      if (!verdict.allowed()) {
        return new Outcome(false, attempt, null, "Refused: " + verdict.problem());
      }
      try {
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest.Builder request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/cloudevents+json")
                .header("X-Pharmacy-Event", eventType)
                .header("webhook-id", messageId)
                .header("webhook-timestamp", Long.toString(timestamp))
                .header("webhook-signature", signatures(secrets, messageId, timestamp, body))
                .header("User-Agent", "pharmacy-core-webhooks/2");
        if (legacySignature) {
          request.header("X-Pharmacy-Signature", "sha256=" + signature(secrets.getFirst(), body));
        }
        HttpResponse<Void> response =
            client.send(
                request
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        status = response.statusCode();
        if (status >= 200 && status < 300) {
          return new Outcome(true, attempt, status, null);
        }
        error = "HTTP " + status;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return new Outcome(false, attempt, status, "interrupted");
      } catch (Exception failure) {
        error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
      }
      if (attempt < MAX_ATTEMPTS) {
        try {
          Thread.sleep(1000L * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return new Outcome(false, attempt, status, error);
        }
      }
    }
    return new Outcome(false, MAX_ATTEMPTS, status, error);
  }

  /** The {@code webhook-signature} value: {@code v1,<sig>} per secret, space-separated. */
  static String signatures(List<String> secrets, String messageId, long timestamp, String body) {
    return secrets.stream()
        .map(secret -> "v1," + standardSignature(secret, messageId, timestamp, body))
        .collect(Collectors.joining(" "));
  }

  /**
   * The Standard Webhooks signature: base64 of HMAC-SHA256 over {@code id.timestamp.body}, keyed
   * with the base64-decoded secret (the part after {@code whsec_}).
   */
  static String standardSignature(String secret, String messageId, long timestamp, String body) {
    String encoded =
        secret.startsWith(Webhook.PLAIN_PREFIX)
            ? secret.substring(Webhook.PLAIN_PREFIX.length())
            : secret;
    byte[] key = Base64.getDecoder().decode(encoded);
    return Base64.getEncoder().encodeToString(hmac(key, messageId + "." + timestamp + "." + body));
  }

  /** The old signature: hex HMAC-SHA256 of the body keyed with the secret's UTF-8 bytes. */
  static String signature(String secret, String body) {
    return HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), body));
  }

  private static byte[] hmac(byte[] key, String content) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
    }
  }
}
