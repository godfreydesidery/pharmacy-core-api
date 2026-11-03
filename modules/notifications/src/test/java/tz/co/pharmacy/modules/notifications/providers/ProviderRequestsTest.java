package tz.co.pharmacy.modules.notifications.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The exact requests each provider receives, built without a network. */
class ProviderRequestsTest {

  @Test
  @DisplayName("Africa's Talking: form body, apiKey header, live or sandbox endpoint")
  void africasTalking() {
    ProviderRequest live =
        AfricasTalkingSmsSender.request(
            "afya", "secret-key", "AFYA", "+255712345678", "Receipt INV-1 & thanks");
    assertThat(live.method()).isEqualTo("POST");
    assertThat(live.uri()).isEqualTo(AfricasTalkingSmsSender.LIVE);
    assertThat(live.headers())
        .containsEntry("apiKey", "secret-key")
        .containsEntry("Content-Type", "application/x-www-form-urlencoded");
    assertThat(live.body())
        .isEqualTo("username=afya&to=%2B255712345678&message=Receipt+INV-1+%26+thanks&from=AFYA");

    ProviderRequest sandbox =
        AfricasTalkingSmsSender.request("sandbox", "k", "", "+255712345678", "hi");
    assertThat(sandbox.uri()).isEqualTo(AfricasTalkingSmsSender.SANDBOX);
    assertThat(sandbox.body()).doesNotContain("from=");
  }

  @Test
  @DisplayName("Twilio: account URL, basic auth, whatsapp: addresses for WhatsApp")
  void twilio() {
    ProviderRequest sms =
        TwilioMessages.request("AC123", "token", "+15550001111", "+255712345678", "Hello", false);
    assertThat(sms.uri().toString())
        .isEqualTo("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json");
    assertThat(sms.headers().get("Authorization"))
        .isEqualTo(
            "Basic "
                + Base64.getEncoder()
                    .encodeToString("AC123:token".getBytes(StandardCharsets.UTF_8)));
    assertThat(sms.body()).isEqualTo("To=%2B255712345678&From=%2B15550001111&Body=Hello");

    ProviderRequest whatsapp =
        TwilioMessages.request("AC123", "token", "+14155238886", "+255712345678", "Hi", true);
    assertThat(whatsapp.body())
        .isEqualTo("To=whatsapp%3A%2B255712345678&From=whatsapp%3A%2B14155238886&Body=Hi");
  }

  @Test
  @DisplayName("Beem: JSON body with the number without its plus, basic auth")
  void beem() {
    ProviderRequest request =
        BeemSmsSender.request("api", "secret", "AFYA", "+255712345678", "Receipt INV-1");
    assertThat(request.uri()).isEqualTo(BeemSmsSender.ENDPOINT);
    assertThat(request.headers())
        .containsEntry("Content-Type", "application/json")
        .containsEntry(
            "Authorization",
            "Basic "
                + Base64.getEncoder()
                    .encodeToString("api:secret".getBytes(StandardCharsets.UTF_8)));
    assertThat(request.body())
        .contains("\"dest_addr\":\"255712345678\"")
        .contains("\"source_addr\":\"AFYA\"")
        .contains("\"message\":\"Receipt INV-1\"")
        .contains("\"recipient_id\":1");
  }
}
