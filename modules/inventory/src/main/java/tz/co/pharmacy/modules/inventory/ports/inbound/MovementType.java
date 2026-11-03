package tz.co.pharmacy.modules.inventory.ports.inbound;

/** How stock changed. */
public enum MovementType {
  OPENING,
  RECEIPT,
  SALE,
  SALE_RETURN,
  ADJUSTMENT,
  TRANSFER_OUT,
  TRANSFER_IN
}
