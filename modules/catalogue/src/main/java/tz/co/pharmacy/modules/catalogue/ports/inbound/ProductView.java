package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
    boolean requiresSerialAtSale)
    implements tz.co.pharmacy.platform.kernel.Versioned {}
