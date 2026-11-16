package tz.co.pharmacy.application.checkout.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.modules.catalogue.ports.inbound.DosageForm;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductType;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.catalogue.ports.inbound.TrackingMode;
import tz.co.pharmacy.modules.catalogue.ports.inbound.UnitOfMeasure;

/** An active product as the POS shows it: its price and the quantity on hand at the branch. */
public record SellableProduct(
    UUID productUid,
    String name,
    String genericName,
    String sku,
    String gtin,
    DosageForm dosageForm,
    String strength,
    UUID categoryUid,
    String categoryName,
    Schedule schedule,
    BigDecimal sellingPrice,
    BigDecimal taxRatePercent,
    int quantityOnHand,
    ProductType productType,
    TrackingMode trackingMode,
    UnitOfMeasure unitOfMeasure,
    boolean requiresSerialAtSale,
    int openableUnits,
    int quarantinedUnits) {}
