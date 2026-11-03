package tz.co.pharmacy.modules.catalogue.ports.inbound;

/**
 * How stock of a product is tracked: by batch with an expiry date (medicines, sold earliest expiry
 * first), by batch without expiry (sold first in, first out), unit by unit with a serial number
 * (equipment such as wheelchairs), or by quantity alone.
 */
public enum TrackingMode {
  BATCH_EXPIRY,
  BATCH,
  SERIAL,
  QUANTITY
}
