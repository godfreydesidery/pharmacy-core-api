package tz.co.pharmacy.modules.notifications.providers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.outbound.WhatsAppSender;

/**
 * WhatsApp through Twilio: {@code pharmacy.notifications.whatsapp.twilio.account-sid}, {@code
 * .auth-token} and the WhatsApp sender {@code .from} (such as {@code +14155238886}).
 */
@Component
@ConditionalOnProperty(name = "pharmacy.notifications.whatsapp.provider", havingValue = "twilio")
class TwilioWhatsAppSender implements WhatsAppSender {

  private final ProviderHttp http;
  private final String accountSid;
  private final String authToken;
  private final String from;

  TwilioWhatsAppSender(
      ProviderHttp http,
      @Value("${pharmacy.notifications.whatsapp.twilio.account-sid:}") String accountSid,
      @Value("${pharmacy.notifications.whatsapp.twilio.auth-token:}") String authToken,
      @Value("${pharmacy.notifications.whatsapp.twilio.from:}") String from) {
    this.http = http;
    this.accountSid = accountSid;
    this.authToken = authToken;
    this.from = from;
  }

  @Override
  public String name() {
    return "twilio-whatsapp";
  }

  @Override
  public void send(String toE164, String text) {
    if (accountSid.isBlank() || authToken.isBlank() || from.isBlank()) {
      throw new ProviderException(
          "twilio whatsapp is not configured: set account-sid, auth-token and from");
    }
    http.send(name(), TwilioMessages.request(accountSid, authToken, from, toE164, text, true));
  }
}
