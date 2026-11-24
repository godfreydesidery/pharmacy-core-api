package tz.co.pharmacy.modules.access.application;

import tz.co.pharmacy.platform.kernel.PhoneNumbers;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * People's phone numbers in E.164 (ITU-T), such as +255754000111, by the rules of {@link
 * PhoneNumbers}. A person's account belongs to no one pharmacy, so a local number (07XXXXXXXX,
 * 06XXXXXXXX) takes Tanzania's code.
 */
final class Phones {

  private Phones() {}

  /** The number in E.164; 422 invalid_phone when it cannot be one. */
  static String normalise(String value) {
    String phone = PhoneNumbers.e164(value, PhoneNumbers.DEFAULT_COUNTRY);
    if (phone == null) {
      throw Refusal.invalid(
          "invalid_phone", "Enter a phone such as 0754 000 111 or +255754000111.", "phone");
    }
    return phone;
  }
}
