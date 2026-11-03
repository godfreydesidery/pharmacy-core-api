package tz.co.pharmacy.modules.inventory.ports.inbound;

/** A product's stock against its reorder level: none, at or below it, or above it. */
public enum StockStatus {
  IN_STOCK,
  LOW_STOCK,
  OUT_OF_STOCK;

  public static StockStatus of(int quantity, int reorderLevel) {
    if (quantity <= 0) {
      return OUT_OF_STOCK;
    }
    return quantity <= reorderLevel ? LOW_STOCK : IN_STOCK;
  }
}
