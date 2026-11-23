package tz.co.pharmacy.modules.sales.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * {@code sale.returned}: goods of a sale came back and were refunded. {@code refundTotal} is in the
 * base currency as booked; a foreign refund adds its currency, amount and the realised exchange
 * difference (base paid out − refundTotal). {@code refunds} splits {@code refundTotal} by the way
 * the sale was paid (null in events from before the split: {@code refundMethod} took it all).
 */
public record SaleReturned(
    EventMetadata metadata,
    UUID returnUid,
    String number,
    UUID saleUid,
    UUID branchUid,
    String refundMethod,
    BigDecimal refundTotal,
    List<SaleCompleted.Line> lines,
    String refundCurrency,
    BigDecimal refundForeignAmount,
    BigDecimal fxDifference,
    List<Refund> refunds)
    implements DomainEvent {

  /** A share of the refund: INSURANCE off the claim, CREDIT/ACCOUNT_CREDIT to the account. */
  public record Refund(String method, BigDecimal amount) {}

  /** The shares, or for an event from before the split the one share of {@code refundMethod}. */
  public List<Refund> shares() {
    return refunds == null || refunds.isEmpty()
        ? List.of(new Refund(refundMethod, refundTotal))
        : refunds;
  }
}
