package tz.co.pharmacy.modules.sales.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * Units of one sale line coming back. For a line of serial-tracked units, {@code serialNumbers}
 * name the units returned; with none, the first not yet returned are. {@code disposition} is where
 * they go; null takes the default: SHELF for an OTC medicine, QUARANTINE for a prescription or
 * controlled one (which may never go to SHELF: 422 restock_not_allowed).
 */
public record ReturnLineCommand(
    UUID saleLineUid, int quantity, List<String> serialNumbers, ReturnDisposition disposition) {

  public ReturnLineCommand(UUID saleLineUid, int quantity, List<String> serialNumbers) {
    this(saleLineUid, quantity, serialNumbers, null);
  }
}
