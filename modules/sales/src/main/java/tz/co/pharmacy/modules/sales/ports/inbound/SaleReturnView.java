package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A return. {@code refundTotal} is the base value booked at the sale. A refund in a foreign
 * currency also gives the amount paid back in it, the sale's and today's rates, and {@code
 * fxDifference} (base paid out − refundTotal): positive a realised exchange loss, negative a gain.
 */
public record SaleReturnView(
    UUID uid,
    String number,
    UUID saleUid,
    String saleNumber,
    String reason,
    PaymentMethod refundMethod,
    BigDecimal refundTotal,
    String refundCurrency,
    BigDecimal refundForeignAmount,
    BigDecimal saleExchangeRate,
    BigDecimal refundExchangeRate,
    BigDecimal fxDifference,
    List<SaleReturnLineView> lines,
    Instant createdAt,
    String createdBy) {}
