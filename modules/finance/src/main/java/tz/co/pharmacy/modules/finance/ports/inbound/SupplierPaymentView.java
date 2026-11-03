package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A supplier payment. {@code amount} is in {@code currency} at {@code exchangeRate}; {@code
 * baseAmount} is what it cost in the base currency, {@code bookedBaseAmount} what the settled
 * receipts were booked at, and {@code fxDifference} = baseAmount − bookedBaseAmount: positive is a
 * realised loss, negative a gain (posted to 7000 FX Gains/Losses).
 */
public record SupplierPaymentView(
    UUID uid,
    String number,
    UUID supplierUid,
    String supplierName,
    BigDecimal amount,
    String currency,
    BigDecimal exchangeRate,
    BigDecimal baseAmount,
    BigDecimal bookedBaseAmount,
    BigDecimal fxDifference,
    String paymentMethod,
    String reference,
    List<UUID> goodsReceiptUids,
    LocalDate paidOn,
    Instant createdAt,
    String createdBy) {}
