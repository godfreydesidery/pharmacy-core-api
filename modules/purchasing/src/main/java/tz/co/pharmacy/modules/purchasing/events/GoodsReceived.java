package tz.co.pharmacy.modules.purchasing.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "goods.received": a goods receipt was recorded at a branch. {@code total} includes {@code
 * vatTotal}, the invoice's input VAT (STD-2; null in events from before it was kept: none).
 */
public record GoodsReceived(
    EventMetadata metadata,
    UUID receiptUid,
    String number,
    UUID branchUid,
    UUID supplierUid,
    String supplierName,
    BigDecimal total,
    List<Line> lines,
    /* The receipt's currency and its rate (base units per unit); {@code total} is in it. */
    String currency,
    BigDecimal exchangeRate,
    /* {@code total} in the tenant's base currency. */
    BigDecimal baseTotal,
    /* The input VAT in {@code total}, in the receipt's currency and in the base. */
    BigDecimal vatTotal,
    BigDecimal baseVatTotal,
    /*
     * baseTotal less the base value of the lines (each converted once) and of the VAT: the
     * conversion's rounding (STD-17), posted to 7000; null in events from before it was kept.
     */
    BigDecimal baseRounding)
    implements DomainEvent {

  public record Line(UUID productUid, String productName, int quantity, BigDecimal unitCost) {}
}
