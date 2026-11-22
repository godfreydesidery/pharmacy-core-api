package tz.co.pharmacy.modules.access.application;

import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Phone numbers in E.164 (ITU-T), such as +255754000111. As people write them in Tanzania, a local
 * 07XXXXXXXX or 06XXXXXXXX number and 255XXXXXXXXX are accepted and put in international form.
 */
final class Phones {

  private Phones() {}

  /** The number in E.164; 422 invalid_phone when it cannot be one. */
  static String normalise(String value) {
    if (value == null || value.isBlank()) {
      throw invalid();
    }
    String phone = value.replaceAll("[\\s\\-().]", "");
    if (phone.startsWith("00")) {
      phone = "+" + phone.substring(2);
    } else if (phone.matches("0[67]\\d{8}")) {
      phone = "+255" + phone.substring(1);
    } else if (phone.matches("255\\d{9}")) {
      phone = "+" + phone;
    }
    if (!phone.matches("\\+[1-9]\\d{7,14}")) {
      throw invalid();
    }
    return phone;
  }

  private static Refusal invalid() {
    return Refusal.invalid(
        "invalid_phone", "Enter a phone such as 0754 000 111 or +255754000111.", "phone");
  }
}
