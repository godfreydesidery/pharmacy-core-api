package tz.co.pharmacy.modules.notifications.application;

/**
 * Phone numbers as E.164, such as {@code +255712345678}. A local number starting with 0 takes the
 * default country code ({@code pharmacy.notifications.default-country-code}, 255 for Tanzania).
 */
final class Phones {

  private Phones() {}

  /** The E.164 form, or null when the input is not a phone number. */
  static String e164(String raw, String defaultCountryCode) {
    if (raw == null) {
      return null;
    }
    String digits = raw.replaceAll("[\\s().-]", "");
    if (digits.startsWith("00")) {
      digits = "+" + digits.substring(2);
    }
    if (digits.startsWith("+")) {
      String rest = digits.substring(1);
      return rest.matches("\\d{8,15}") ? "+" + rest : null;
    }
    if (digits.startsWith("0") && digits.length() >= 9) {
      String national = digits.substring(1);
      return national.matches("\\d{8,12}") ? "+" + defaultCountryCode + national : null;
    }
    return digits.matches("\\d{10,15}") ? "+" + digits : null;
  }
}
