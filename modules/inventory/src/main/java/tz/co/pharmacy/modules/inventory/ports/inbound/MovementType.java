package tz.co.pharmacy.modules.inventory.ports.inbound;

/** How stock changed. */
public enum MovementType {
  OPENING,
  RECEIPT,
  SALE,
  SALE_RETURN,
  ADJUSTMENT,
  TRANSFER_OUT,
  TRANSFER_IN,
  /** An input of a transformation (break bulk, repack, compounding). */
  TRANSFORM_OUT,
  /** The output of a transformation. */
  TRANSFORM_IN,
  /** Stock sent back to its supplier. */
  SUPPLIER_RETURN
}
