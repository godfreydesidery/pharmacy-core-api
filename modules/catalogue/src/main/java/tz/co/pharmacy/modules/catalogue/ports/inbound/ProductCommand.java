package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A product's fields. {@code schedule} defaults to OTC, {@code storage} to ROOM_TEMPERATURE, {@code
 * taxRatePercent} and {@code reorderLevel} to 0, {@code productType} to MEDICINE, {@code
 * trackingMode} to the type's default and {@code unitOfMeasure} to PIECE. The clinical fields
 * ({@code genericName}, {@code dosageForm}) are required for a MEDICINE only; another type has no
 * dosage form and is sold over the counter. {@code brand}, {@code model} and {@code warrantyMonths}
 * describe equipment. {@code requiresSerialAtSale} applies to SERIAL tracking only (default true).
 */
public record ProductCommand(
    String name,
    String genericName,
    String sku,
    String gtin,
    String atcCode,
    UUID categoryUid,
    UUID manufacturerUid,
    DosageForm dosageForm,
    String strength,
    String packSize,
    Schedule schedule,
    BigDecimal sellingPrice,
    BigDecimal costPrice,
    BigDecimal taxRatePercent,
    Integer reorderLevel,
    Storage storage,
    ProductType productType,
    TrackingMode trackingMode,
    UnitOfMeasure unitOfMeasure,
    String brand,
    String model,
    Integer warrantyMonths,
    Boolean requiresSerialAtSale,
    /* The TMDA registration (marketing authorisation) number, optional (STD-8). */
    String tmdaRegistrationNumber) {

  /** A product without a TMDA registration number. */
  public ProductCommand(
      String name,
      String genericName,
      String sku,
      String gtin,
      String atcCode,
      UUID categoryUid,
      UUID manufacturerUid,
      DosageForm dosageForm,
      String strength,
      String packSize,
      Schedule schedule,
      BigDecimal sellingPrice,
      BigDecimal costPrice,
      BigDecimal taxRatePercent,
      Integer reorderLevel,
      Storage storage,
      ProductType productType,
      TrackingMode trackingMode,
      UnitOfMeasure unitOfMeasure,
      String brand,
      String model,
      Integer warrantyMonths,
      Boolean requiresSerialAtSale) {
    this(
        name,
        genericName,
        sku,
        gtin,
        atcCode,
        categoryUid,
        manufacturerUid,
        dosageForm,
        strength,
        packSize,
        schedule,
        sellingPrice,
        costPrice,
        taxRatePercent,
        reorderLevel,
        storage,
        productType,
        trackingMode,
        unitOfMeasure,
        brand,
        model,
        warrantyMonths,
        requiresSerialAtSale,
        null);
  }
}
