package tz.co.pharmacy.modules.notifications.providers;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.outbound.SmsSender;

/**
 * SMS through Africa's Talking (Tanzania, Kenya, Uganda, Rwanda and more): {@code
 * pharmacy.notifications.sms.africastalking.username}, {@code .api-key} and the optional {@code
 * .sender-id}. The username {@code sandbox} uses the sandbox API.
 */
@Component
@ConditionalOnProperty(name = "pharmacy.notifications.sms.provider", havingValue = "africastalking")
class AfricasTalkingSmsSender implements SmsSender {

  static final URI LIVE = URI.create("https://api.africastalking.com/version1/messaging");
  static final URI SANDBOX =
      URI.create("https://api.sandbox.africastalking.com/version1/messaging");

  private final ProviderHttp http;
  private final String username;
  private final String apiKey;
  private final String senderId;

  AfricasTalkingSmsSender(
      ProviderHttp http,
      @Value("${pharmacy.notifications.sms.africastalking.username:}") String username,
      @Value("${pharmacy.notifications.sms.africastalking.api-key:}") String apiKey,
      @Value("${pharmacy.notifications.sms.africastalking.sender-id:}") String senderId) {
    this.http = http;
    this.username = username;
    this.apiKey = apiKey;
    this.senderId = senderId;
  }

  @Override
  public String name() {
    return "africastalking";
  }

  @Override
  public void send(String toE164, String text) {
    if (username.isBlank() || apiKey.isBlank()) {
      throw new ProviderException("africastalking is not configured: set username and api-key");
    }
    http.send(name(), request(username, apiKey, senderId, toE164, text));
  }

  static ProviderRequest request(
      String username, String apiKey, String senderId, String toE164, String text) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("username", username);
    form.put("to", toE164);
    form.put("message", text);
    form.put("from", senderId == null || senderId.isBlank() ? null : senderId);
    return new ProviderRequest(
        "POST",
        "sandbox".equals(username) ? SANDBOX : LIVE,
        Map.of(
            "apiKey", apiKey,
            "Accept", "application/json",
            "Content-Type", "application/x-www-form-urlencoded"),
        Forms.encode(form));
  }
}
