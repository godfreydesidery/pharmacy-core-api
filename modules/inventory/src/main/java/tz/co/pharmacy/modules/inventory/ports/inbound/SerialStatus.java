package tz.co.pharmacy.modules.inventory.ports.inbound;

/** Where a serial-tracked unit is in its life. */
public enum SerialStatus {
  IN_STOCK,
  SOLD,
  RETURNED,
  IN_TRANSIT,
  WRITTEN_OFF
}
