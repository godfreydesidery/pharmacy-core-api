package tz.co.pharmacy.modules.integrations.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Sends one signed CloudEvent: {@code POST} with {@code Content-Type: application/cloudevents+json}
 * and {@code X-Pharmacy-Signature: sha256=<hex HMAC-SHA256 of the body with the webhook's secret>},
 * so the receiver can prove the body came from us unchanged. Up to three attempts, 1 s and 2 s
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

  WebhookSender(WebhookTargets targets) {
    this.targets = targets;
  }

  record Outcome(boolean delivered, int attempts, Integer responseStatus, String error) {}

  Outcome send(String url, String secret, String eventType, String body) {
    Integer status = null;
    String error = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      // Checked again before every attempt: the host may now resolve to a private address.
      WebhookTargets.Verdict verdict = targets.check(url);
      if (!verdict.allowed()) {
        return new Outcome(false, attempt, null, "Refused: " + verdict.problem());
      }
      try {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/cloudevents+json")
                .header("X-Pharmacy-Event", eventType)
                .header("X-Pharmacy-Signature", "sha256=" + signature(secret, body))
                .header("User-Agent", "pharmacy-core-webhooks/1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
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

  static String signature(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
    }
  }
}
