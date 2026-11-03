package tz.co.pharmacy.modules.catalogue.application;

import tz.co.pharmacy.modules.catalogue.ports.inbound.DosageForm;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductType;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.catalogue.ports.inbound.TrackingMode;
import tz.co.pharmacy.modules.catalogue.ports.inbound.UnitOfMeasure;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The rules that depend on what kind of product it is. A MEDICINE needs its generic name and dosage
 * form and may be prescription-only; anything else (a wheelchair, gloves, a cream) has no dosage
 * form and is sold over the counter. SERIAL tracking is for items counted unit by unit, such as
 * equipment; it asks for the serial number at sale unless told otherwise.
 */
final class ProductRules {

  static final int MAX_WARRANTY_MONTHS = 120;

  private ProductRules() {}

  /** The type-dependent fields of a product, validated. */
  record Kind(
      ProductType productType,
      TrackingMode trackingMode,
      UnitOfMeasure unitOfMeasure,
      String genericName,
      DosageForm dosageForm,
      Schedule schedule,
      String brand,
      String model,
      Integer warrantyMonths,
      boolean requiresSerialAtSale) {

    boolean medicine() {
      return productType == ProductType.MEDICINE;
    }
  }

  static Kind kind(ProductCommand c) {
    ProductType type = c.productType() == null ? ProductType.MEDICINE : c.productType();
    TrackingMode tracking = c.trackingMode() == null ? type.defaultTracking() : c.trackingMode();
    UnitOfMeasure unit = c.unitOfMeasure() == null ? UnitOfMeasure.PIECE : c.unitOfMeasure();
    String genericName = Checks.optionalText(c.genericName(), "genericName", 200);
    DosageForm dosageForm = c.dosageForm();
    Schedule schedule = c.schedule() == null ? Schedule.OTC : c.schedule();
    if (type == ProductType.MEDICINE) {
      if (genericName == null) {
        throw Refusal.invalid(
            "invalid_generic_name", "A medicine needs its generic (INN) name.", "genericName");
      }
      if (dosageForm == null) {
        throw Refusal.invalid(
            "invalid_dosage_form", "A medicine needs its dosage form.", "dosageForm");
      }
    } else {
      dosageForm = null;
      schedule = Schedule.OTC;
    }
    Integer warranty = c.warrantyMonths();
    if (warranty != null && (warranty < 0 || warranty > MAX_WARRANTY_MONTHS)) {
      throw Refusal.invalid(
          "invalid_warranty_months",
          "warrantyMonths must be between 0 and " + MAX_WARRANTY_MONTHS + ".",
          "warrantyMonths");
    }
    boolean serialAtSale =
        tracking == TrackingMode.SERIAL
            && (c.requiresSerialAtSale() == null || c.requiresSerialAtSale());
    return new Kind(
        type,
        tracking,
        unit,
        genericName,
        dosageForm,
        schedule,
        Checks.optionalText(c.brand(), "brand", 100),
        Checks.optionalText(c.model(), "model", 100),
        warranty,
        serialAtSale);
  }
}
