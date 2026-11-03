package tz.co.pharmacy.modules.notifications.providers;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.notifications.ports.outbound.SmsSender;

/**
 * SMS through Beem Africa (Tanzania): {@code pharmacy.notifications.sms.beem.api-key}, {@code
 * .secret-key} and the registered {@code .sender-id}.
 */
@Component
@ConditionalOnProperty(name = "pharmacy.notifications.sms.provider", havingValue = "beem")
class BeemSmsSender implements SmsSender {

  static final URI ENDPOINT = URI.create("https://apisms.beem.africa/v1/send");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ProviderHttp http;
  private final String apiKey;
  private final String secretKey;
  private final String senderId;

  BeemSmsSender(
      ProviderHttp http,
      @Value("${pharmacy.notifications.sms.beem.api-key:}") String apiKey,
      @Value("${pharmacy.notifications.sms.beem.secret-key:}") String secretKey,
      @Value("${pharmacy.notifications.sms.beem.sender-id:INFO}") String senderId) {
    this.http = http;
    this.apiKey = apiKey;
    this.secretKey = secretKey;
    this.senderId = senderId;
  }

  @Override
  public String name() {
    return "beem";
  }

  @Override
  public void send(String toE164, String text) {
    if (apiKey.isBlank() || secretKey.isBlank()) {
      throw new ProviderException("beem is not configured: set api-key and secret-key");
    }
    http.send(name(), request(apiKey, secretKey, senderId, toE164, text));
  }

  /** Beem takes the number without its plus, such as 255712345678. */
  static ProviderRequest request(
      String apiKey, String secretKey, String senderId, String toE164, String text) {
    Map<String, Object> body =
        Map.of(
            "source_addr",
            senderId,
            "schedule_time",
            "",
            "encoding",
            0,
            "message",
            text,
            "recipients",
            List.of(
                Map.of(
                    "recipient_id",
                    1,
                    "dest_addr",
                    toE164.startsWith("+") ? toE164.substring(1) : toE164)));
    return new ProviderRequest(
        "POST",
        ENDPOINT,
        Map.of(
            "Authorization", Forms.basic(apiKey, secretKey),
            "Content-Type", "application/json",
            "Accept", "application/json"),
        JSON.writeValueAsString(body));
  }
}
