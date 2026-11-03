package tz.co.pharmacy.modules.purchasing.application;

import java.util.List;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReceiptLineCommand;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * What a receipt line must carry for its product's tracking mode: a medicine (BATCH_EXPIRY) its
 * batch and expiry, a BATCH product its batch, a SERIAL product (a wheelchair) one serial number
 * per unit, a QUANTITY product nothing more than the quantity.
 */
final class ReceiptTracking {

  /** The batch number of a product without batches of its own. */
  static final String IMPLICIT_BATCH = "-";

  private ReceiptTracking() {}

  static void check(String tracking, ReceiptLineCommand line, int index) {
    String field = "lines[" + index + "]";
    switch (tracking == null ? "BATCH_EXPIRY" : tracking) {
      case "BATCH_EXPIRY" -> {
        Checks.text(line.batchNumber(), field + ".batchNumber", 64);
        Checks.required(line.expiryDate(), field + ".expiryDate");
      }
      case "BATCH" -> Checks.text(line.batchNumber(), field + ".batchNumber", 64);
      case "SERIAL" -> {
        Checks.optionalText(line.batchNumber(), field + ".batchNumber", 64);
        List<String> serials = serials(line);
        if (serials.size() != line.quantity()) {
          throw Refusal.invalid(
              "serials_mismatch",
              "Give one serial number per unit: "
                  + line.quantity()
                  + " unit(s), "
                  + serials.size()
                  + " serial number(s).",
              field + ".serialNumbers");
        }
        if (serials.stream().distinct().count() != serials.size()) {
          throw Refusal.conflict("duplicate_serial", "A serial number is listed twice.")
              .about(field + ".serialNumbers");
        }
      }
      default -> {
        // QUANTITY: the quantity is all there is.
      }
    }
    if (!"SERIAL".equals(tracking) && !serials(line).isEmpty()) {
      throw Refusal.invalid(
          "serials_not_tracked",
          "This product is not tracked by serial number.",
          field + ".serialNumbers");
    }
  }

  static String batchNumber(ReceiptLineCommand line) {
    return line.batchNumber() == null || line.batchNumber().isBlank()
        ? IMPLICIT_BATCH
        : line.batchNumber().trim();
  }

  /** The line's serial numbers, trimmed, blanks dropped. */
  static List<String> serials(ReceiptLineCommand line) {
    return line.serialNumbers() == null
        ? List.of()
        : line.serialNumbers().stream()
            .filter(serial -> serial != null && !serial.isBlank())
            .map(String::trim)
            .toList();
  }
}
