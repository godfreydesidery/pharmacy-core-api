package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

/** The catalogue facts inventory keeps about a product, to list and value stock. */
public record ProductSnapshot(
    UUID productUid,
    String name,
    String genericName,
    String sku,
    String dosageForm,
    String strength,
    UUID categoryUid,
    String categoryName,
    int reorderLevel,
    BigDecimal sellingPrice,
    boolean active,
    String productType,
    String trackingMode,
    String unitOfMeasure) {}
