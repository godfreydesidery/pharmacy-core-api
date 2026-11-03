package tz.co.pharmacy.modules.catalogue.ports.inbound;

/** What kind of product it is; medicines carry clinical fields, equipment a brand and warranty. */
public enum ProductType {
  MEDICINE,
  MEDICAL_EQUIPMENT,
  MEDICAL_SUPPLY,
  COSMETIC,
  SUPPLEMENT,
  BABY_CARE,
  OTHER;

  /** The tracking a product of this type gets when none is chosen. */
  public TrackingMode defaultTracking() {
    return switch (this) {
      case MEDICINE -> TrackingMode.BATCH_EXPIRY;
      case MEDICAL_EQUIPMENT -> TrackingMode.SERIAL;
      default -> TrackingMode.BATCH;
    };
  }
}
