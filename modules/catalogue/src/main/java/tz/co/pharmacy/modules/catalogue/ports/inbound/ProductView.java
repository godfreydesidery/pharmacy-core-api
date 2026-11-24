package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A product. {@code taxRatePercent} is the VAT rate it is sold at while the pharmacy is
 * VAT-registered, derived from {@code taxCategory}: the standard rate for STANDARD, 0 for
 * ZERO_RATED and EXEMPT. A pharmacy that is not registered charges no VAT on any of them.
 */
public record ProductView(
    UUID uid,
    String name,
    String genericName,
    String sku,
    String gtin,
    String atcCode,
    UUID categoryUid,
    String categoryName,
    UUID manufacturerUid,
    String manufacturerName,
    DosageForm dosageForm,
    String strength,
    String packSize,
    Schedule schedule,
    BigDecimal sellingPrice,
    BigDecimal costPrice,
    BigDecimal taxRatePercent,
    int reorderLevel,
    Storage storage,
    boolean active,
    long version,
    Instant createdAt,
    ProductType productType,
    TrackingMode trackingMode,
    UnitOfMeasure unitOfMeasure,
    String brand,
    String model,
    Integer warrantyMonths,
    boolean requiresSerialAtSale,
    tz.co.pharmacy.platform.kernel.TaxCategory taxCategory,
    /* The TMDA registration number (STD-8), optional. */
    String tmdaRegistrationNumber)
    implements tz.co.pharmacy.platform.kernel.Versioned {}
