package tz.co.pharmacy.modules.notifications.providers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.outbound.SmsSender;

/**
 * SMS through Twilio: {@code pharmacy.notifications.sms.twilio.account-sid}, {@code .auth-token}
 * and the sending number or messaging service {@code .from}.
 */
@Component
@ConditionalOnProperty(name = "pharmacy.notifications.sms.provider", havingValue = "twilio")
class TwilioSmsSender implements SmsSender {

  private final ProviderHttp http;
  private final String accountSid;
  private final String authToken;
  private final String from;

  TwilioSmsSender(
      ProviderHttp http,
      @Value("${pharmacy.notifications.sms.twilio.account-sid:}") String accountSid,
      @Value("${pharmacy.notifications.sms.twilio.auth-token:}") String authToken,
      @Value("${pharmacy.notifications.sms.twilio.from:}") String from) {
    this.http = http;
    this.accountSid = accountSid;
    this.authToken = authToken;
    this.from = from;
  }

  @Override
  public String name() {
    return "twilio";
  }

  @Override
  public void send(String toE164, String text) {
    if (accountSid.isBlank() || authToken.isBlank() || from.isBlank()) {
      throw new ProviderException("twilio is not configured: set account-sid, auth-token and from");
    }
    http.send(name(), TwilioMessages.request(accountSid, authToken, from, toE164, text, false));
  }
}
