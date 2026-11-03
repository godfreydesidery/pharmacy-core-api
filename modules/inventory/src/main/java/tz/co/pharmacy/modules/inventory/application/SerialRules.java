package tz.co.pharmacy.modules.inventory.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tz.co.pharmacy.platform.kernel.Refusal;

/** The checks every list of serial numbers passes: trimmed, unique, of a sane shape and count. */
final class SerialRules {

  static final int MAX_LENGTH = 64;

  private SerialRules() {}

  /** The serial numbers trimmed, blanks dropped; 422 invalid_serial or duplicate_serial. */
  static List<String> normalise(List<String> serials, String field) {
    if (serials == null || serials.isEmpty()) {
      return List.of();
    }
    List<String> clean = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String serial : serials) {
      if (serial == null || serial.isBlank()) {
        continue;
      }
      String value = serial.trim();
      if (value.length() > MAX_LENGTH || value.contains(",")) {
        throw Refusal.invalid(
            "invalid_serial",
            "A serial number has at most " + MAX_LENGTH + " characters and no comma.",
            field);
      }
      if (!seen.add(value)) {
        throw Refusal.conflict("duplicate_serial", "Serial " + value + " is listed twice.")
            .about(field);
      }
      clean.add(value);
    }
    return List.copyOf(clean);
  }

  /** 422 serials_mismatch unless there is one serial number per unit. */
  static void requireCount(List<String> serials, int quantity, String field) {
    if (serials.size() != quantity) {
      throw Refusal.invalid(
          "serials_mismatch",
          "Give one serial number per unit: "
              + quantity
              + " unit(s), "
              + serials.size()
              + " serial number(s).",
          field);
    }
  }
}
