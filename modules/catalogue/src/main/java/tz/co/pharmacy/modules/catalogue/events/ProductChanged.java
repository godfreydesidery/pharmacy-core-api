package tz.co.pharmacy.modules.catalogue.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * A product was created or changed ("product.changed"): the fields other modules keep a copy of.
 */
public record ProductChanged(
    EventMetadata metadata,
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
    String unitOfMeasure)
    implements DomainEvent {}
