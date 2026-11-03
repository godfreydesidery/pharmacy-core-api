package tz.co.pharmacy.modules.notifications.providers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/** Form encoding and basic authentication for the HTTP providers. */
final class Forms {

  private Forms() {}

  /** {@code application/x-www-form-urlencoded}, skipping null values, in the map's order. */
  static String encode(Map<String, String> fields) {
    return fields.entrySet().stream()
        .filter(field -> field.getValue() != null)
        .map(
            field ->
                URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  static String basic(String user, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }
}
