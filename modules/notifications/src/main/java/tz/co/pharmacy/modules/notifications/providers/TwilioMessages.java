package tz.co.pharmacy.modules.notifications.providers;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** The Twilio Messages API request, for SMS and (with {@code whatsapp:} addresses) WhatsApp. */
final class TwilioMessages {

  private TwilioMessages() {}

  static ProviderRequest request(
      String accountSid,
      String authToken,
      String from,
      String toE164,
      String text,
      boolean whatsapp) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("To", whatsapp ? "whatsapp:" + toE164 : toE164);
    form.put("From", whatsapp && !from.startsWith("whatsapp:") ? "whatsapp:" + from : from);
    form.put("Body", text);
    return new ProviderRequest(
        "POST",
        URI.create("https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json"),
        Map.of(
            "Authorization", Forms.basic(accountSid, authToken),
            "Accept", "application/json",
            "Content-Type", "application/x-www-form-urlencoded"),
        Forms.encode(form));
  }
}
