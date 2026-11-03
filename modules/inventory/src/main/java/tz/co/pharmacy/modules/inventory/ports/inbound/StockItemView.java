package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One product's stock at the current branch. */
public record StockItemView(
    UUID productUid,
    String productName,
    String genericName,
    String sku,
    String dosageForm,
    String strength,
    String categoryName,
    int quantity,
    int reorderLevel,
    StockStatus status,
    LocalDate nearestExpiry,
    int batchCount,
    BigDecimal sellingPrice,
    BigDecimal costValue,
    String productType,
    String unitOfMeasure,
    String trackingMode) {}
