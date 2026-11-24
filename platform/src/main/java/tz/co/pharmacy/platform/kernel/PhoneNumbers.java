package tz.co.pharmacy.platform.kernel;

import java.util.Map;
import java.util.Set;

/**
 * Phone numbers in E.164 (ITU-T), such as {@code +255754000111}, the one form every phone is stored
 * in (STD-19): a number written as people write it locally takes the pharmacy's country code. For a
 * Tanzanian pharmacy {@code 0754 000 111}, {@code 754000111}, {@code 255754000111}, {@code
 * 00255754000111} and {@code +255 (0)754-000-111} are all {@code +255754000111}.
 *
 * <p>The same rules, in SQL, normalised the phones stored before (partners V10, tenancy V17, access
 * V23); a value they could not read was left as it was.
 */
public final class PhoneNumbers {

  /** The country of a pharmacy that names none: Tanzania. */
  public static final String DEFAULT_COUNTRY = "TZ";

  /** ITU-T E.164 country calling codes of the countries a pharmacy is likely to be in or call. */
  private static final Map<String, String> CALLING_CODES =
      Map.ofEntries(
          Map.entry("TZ", "255"),
          Map.entry("KE", "254"),
          Map.entry("UG", "256"),
          Map.entry("RW", "250"),
          Map.entry("BI", "257"),
          Map.entry("CD", "243"),
          Map.entry("MZ", "258"),
          Map.entry("ZM", "260"),
          Map.entry("MW", "265"),
          Map.entry("ZA", "27"),
          Map.entry("ET", "251"),
          Map.entry("SS", "211"),
          Map.entry("SO", "252"),
          Map.entry("AE", "971"),
          Map.entry("IN", "91"),
          Map.entry("CN", "86"),
          Map.entry("GB", "44"),
          Map.entry("US", "1"));

  /** Countries whose national (significant) numbers are always nine digits. */
  private static final Set<String> NINE_DIGITS = Set.of("255", "254", "256", "250");

  private PhoneNumbers() {}

  /** The calling code of an ISO 3166-1 alpha-2 country, Tanzania's (255) for an unknown one. */
  public static String callingCode(String country) {
    return CALLING_CODES.getOrDefault(
        country == null ? DEFAULT_COUNTRY : country.trim().toUpperCase(java.util.Locale.ROOT),
        "255");
  }

  /**
   * {@code raw} in E.164, a local number taking {@code country}'s calling code; null when it cannot
   * be a phone number (or is blank).
   */
  public static String e164(String raw, String country) {
    if (raw == null) {
      return null;
    }
    String phone = raw.replaceAll("[\\s\\-().]", "");
    if (phone.isEmpty()) {
      return null;
    }
    String code = callingCode(country);
    if (phone.startsWith("00")) {
      phone = "+" + phone.substring(2);
    }
    if (phone.startsWith("+")) {
      // "+255 (0)754…": the national trunk 0 written after the country code.
      if (phone.startsWith("+" + code + "0")) {
        phone = "+" + code + phone.substring(code.length() + 2);
      }
    } else if (phone.matches("0\\d+")) {
      phone = "+" + code + phone.substring(1);
    } else if (phone.matches("\\d+")
        && phone.startsWith(code)
        && phone.length() > code.length() + 6) {
      phone = "+" + phone;
    } else if ("255".equals(code) && phone.matches("[67]\\d{8}")) {
      // A Tanzanian mobile written without its 0.
      phone = "+255" + phone;
    } else {
      return null;
    }
    if (!phone.matches("\\+[1-9]\\d{7,14}")) {
      return null;
    }
    for (String nine : NINE_DIGITS) {
      if (phone.startsWith("+" + nine) && phone.length() != nine.length() + 10) {
        return null;
      }
    }
    return phone;
  }

  /**
   * A phone to store: {@code given} in E.164, null when blank. A phone kept from before that these
   * rules cannot read (the backfills left it as it was) stays as it is while it is sent back
   * unchanged ({@code current}), so the rest of its record can still be edited; anything else
   * unreadable is 422 invalid_phone on {@code field}.
   */
  public static String forStorage(String given, String current, String country, String field) {
    if (given == null || given.isBlank()) {
      return null;
    }
    String trimmed = given.trim();
    if (trimmed.equals(current)) {
      return current;
    }
    String e164 = e164(trimmed, country);
    if (e164 == null) {
      throw Refusal.invalid(
          "invalid_phone", "Enter a phone such as 0754 000 111 or +255754000111.", field);
    }
    return e164;
  }

  /**
   * The digits to look for in stored (E.164) phones when someone searches by a number as they write
   * it: "0754 000" finds +255754000111. Null when {@code text} is not a number of at least three
   * digits.
   */
  public static String searchDigits(String text) {
    if (text == null) {
      return null;
    }
    String digits = text.replaceAll("[\\s\\-().]", "");
    if (digits.startsWith("+")) {
      digits = digits.substring(1);
    } else if (digits.startsWith("00")) {
      digits = digits.substring(2);
    } else if (digits.startsWith("0")) {
      digits = digits.substring(1);
    }
    return digits.matches("\\d{3,15}") ? digits : null;
  }
}
