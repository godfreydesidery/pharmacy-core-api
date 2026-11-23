package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;

/**
 * One share of a return's refund: the way the sale was paid and what went back by it, in base.
 * INSURANCE came off the claim, CREDIT and ACCOUNT_CREDIT went to the customer's account, VOUCHER
 * back onto the voucher named by reference; the rest was paid out by that method.
 */
public record SaleRefundView(PaymentMethod method, BigDecimal amount, String reference) {}
