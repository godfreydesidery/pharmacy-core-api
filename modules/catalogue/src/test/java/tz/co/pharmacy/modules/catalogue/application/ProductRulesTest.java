package tz.co.pharmacy.modules.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.catalogue.ports.inbound.DosageForm;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductType;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.catalogue.ports.inbound.TrackingMode;
import tz.co.pharmacy.modules.catalogue.ports.inbound.UnitOfMeasure;
import tz.co.pharmacy.platform.kernel.Refusal;

class ProductRulesTest {

  @Test
  @DisplayName("a medicine needs its generic name and dosage form, and keeps its schedule")
  void medicineNeedsClinicalFields() {
    assertThatThrownBy(
            () -> ProductRules.kind(command(ProductType.MEDICINE, null, DosageForm.TABLET, null)))
        .isInstanceOf(Refusal.class)
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("invalid_generic_name");
    assertThatThrownBy(
            () -> ProductRules.kind(command(ProductType.MEDICINE, "Amoxicillin", null, null)))
        .isInstanceOf(Refusal.class)
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("invalid_dosage_form");

    ProductRules.Kind kind =
        ProductRules.kind(
            withSchedule(
                command(ProductType.MEDICINE, "Amoxicillin", DosageForm.CAPSULE, null),
                Schedule.PRESCRIPTION));
    assertThat(kind.trackingMode()).isEqualTo(TrackingMode.BATCH_EXPIRY);
    assertThat(kind.schedule()).isEqualTo(Schedule.PRESCRIPTION);
    assertThat(kind.requiresSerialAtSale()).isFalse();
  }

  @Test
  @DisplayName("equipment has no dosage form, is sold over the counter and is serial-tracked")
  void equipmentIsSerialTracked() {
    ProductRules.Kind kind =
        ProductRules.kind(
            withSchedule(
                command(ProductType.MEDICAL_EQUIPMENT, null, DosageForm.TABLET, 12),
                Schedule.PRESCRIPTION));
    assertThat(kind.dosageForm()).isNull();
    assertThat(kind.schedule()).isEqualTo(Schedule.OTC);
    assertThat(kind.trackingMode()).isEqualTo(TrackingMode.SERIAL);
    assertThat(kind.requiresSerialAtSale()).isTrue();
    assertThat(kind.warrantyMonths()).isEqualTo(12);
  }

  @Test
  @DisplayName("supplies default to batch tracking; a chosen tracking mode wins")
  void trackingDefaults() {
    assertThat(
            ProductRules.kind(command(ProductType.MEDICAL_SUPPLY, null, null, null)).trackingMode())
        .isEqualTo(TrackingMode.BATCH);
    ProductCommand quantityOnly =
        new ProductCommand(
            "Crutches (Pair)",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.ONE,
            BigDecimal.ONE,
            null,
            null,
            null,
            ProductType.MEDICAL_EQUIPMENT,
            TrackingMode.QUANTITY,
            UnitOfMeasure.PAIR,
            null,
            null,
            null,
            true);
    ProductRules.Kind kind = ProductRules.kind(quantityOnly);
    assertThat(kind.trackingMode()).isEqualTo(TrackingMode.QUANTITY);
    assertThat(kind.unitOfMeasure()).isEqualTo(UnitOfMeasure.PAIR);
    assertThat(kind.requiresSerialAtSale()).isFalse();
  }

  @Test
  @DisplayName("a warranty is between 0 and 120 months")
  void warrantyRange() {
    assertThatThrownBy(
            () -> ProductRules.kind(command(ProductType.MEDICAL_EQUIPMENT, null, null, 121)))
        .isInstanceOf(Refusal.class)
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("invalid_warranty_months");
  }

  private static ProductCommand command(
      ProductType type, String genericName, DosageForm form, Integer warrantyMonths) {
    return new ProductCommand(
        "Product",
        genericName,
        null,
        null,
        null,
        null,
        null,
        form,
        null,
        null,
        null,
        BigDecimal.ONE,
        BigDecimal.ONE,
        null,
        null,
        null,
        type,
        null,
        null,
        "Brand",
        "Model",
        warrantyMonths,
        null);
  }

  private static ProductCommand withSchedule(ProductCommand c, Schedule schedule) {
    return new ProductCommand(
        c.name(),
        c.genericName(),
        c.sku(),
        c.gtin(),
        c.atcCode(),
        c.categoryUid(),
        c.manufacturerUid(),
        c.dosageForm(),
        c.strength(),
        c.packSize(),
        schedule,
        c.sellingPrice(),
        c.costPrice(),
        c.taxRatePercent(),
        c.reorderLevel(),
        c.storage(),
        c.productType(),
        c.trackingMode(),
        c.unitOfMeasure(),
        c.brand(),
        c.model(),
        c.warrantyMonths(),
        c.requiresSerialAtSale());
  }
}
