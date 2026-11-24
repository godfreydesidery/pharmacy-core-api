package tz.co.pharmacy.modules.catalogue.domain;

/**
 * A GS1 Global Trade Item Number, the number under a product's barcode: GTIN-8 (EAN-8), GTIN-12
 * (UPC-A), GTIN-13 (EAN-13) or GTIN-14, digits only, the last one a check digit (GS1 General
 * Specifications §7.9: from the right, the digits are weighted 3, 1, 3, 1…; the check digit brings
 * their sum to a multiple of 10). A mistyped or misread digit almost always breaks it.
 */
public final class Gtin {

  private Gtin() {}

  /** Whether {@code value} is 8, 12, 13 or 14 digits with a correct check digit. */
  public static boolean valid(String value) {
    if (value == null) {
      return false;
    }
    int length = value.length();
    if (length != 8 && length != 12 && length != 13 && length != 14) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        return false;
      }
    }
    return value.charAt(length - 1) - '0' == checkDigit(value.substring(0, length - 1));
  }

  /** The check digit that completes {@code body} (the GTIN without its last digit). */
  public static int checkDigit(String body) {
    int sum = 0;
    for (int i = 0; i < body.length(); i++) {
      int digit = body.charAt(body.length() - 1 - i) - '0';
      sum += i % 2 == 0 ? digit * 3 : digit;
    }
    return (10 - sum % 10) % 10;
  }
}
