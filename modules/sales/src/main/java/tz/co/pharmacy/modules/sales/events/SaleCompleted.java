package tz.co.pharmacy.modules.sales.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * {@code sale.completed}: a sale was recorded. Per line, {@code net} is the line total less tax and
 * {@code cost} the batches' unit cost × quantity.
 */
public record SaleCompleted(
    EventMetadata metadata,
    UUID saleUid,
    String number,
    UUID branchUid,
    UUID customerUid,
    BigDecimal subtotal,
    BigDecimal discountTotal,
    BigDecimal taxTotal,
    BigDecimal total,
    BigDecimal changeDue,
    List<Line> lines,
    List<Payment> payments)
    implements DomainEvent {

  public record Line(
      UUID productUid,
      String productName,
      int quantity,
      BigDecimal net,
      BigDecimal tax,
      BigDecimal cost) {}

  /** {@code amount} is in {@code currency}; {@code baseAmount} in the tenant's base currency. */
  public record Payment(
      String method,
      BigDecimal amount,
      String currency,
      BigDecimal exchangeRate,
      BigDecimal baseAmount) {}
}
