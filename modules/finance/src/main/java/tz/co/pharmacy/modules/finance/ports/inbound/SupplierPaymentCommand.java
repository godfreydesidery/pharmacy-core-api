package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment to a supplier of {@code amount} in {@code currency} (null means the base) at {@code
 * exchangeRate} base units per unit (null means today's rate), settling the listed goods receipts.
 * The receipts are optional; when given they must be the supplier's and in the payment's currency.
 * {@code paymentMethod} is CASH, BANK_TRANSFER, CARD, CHEQUE or MOBILE_MONEY; {@code paidOn}
 * defaults to today.
 */
public record SupplierPaymentCommand(
    UUID supplierUid,
    BigDecimal amount,
    String currency,
    BigDecimal exchangeRate,
    String paymentMethod,
    String reference,
    List<UUID> goodsReceiptUids,
    LocalDate paidOn) {}
