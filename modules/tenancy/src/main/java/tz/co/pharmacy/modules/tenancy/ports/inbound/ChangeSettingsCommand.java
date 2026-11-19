package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.math.BigDecimal;

/** A null field keeps its current value. */
public record ChangeSettingsCommand(
    BigDecimal defaultTaxRatePercent,
    String invoicePrefix,
    String receiptFooter,
    BigDecimal maxDiscountPercent,
    Integer expiryAlertDays,
    Boolean lowStockAlerts,
    Boolean smsReceipts,
    /* The currencies the tenant accepts; the base is always included. Null keeps them. */
    java.util.List<String> acceptedCurrencies,
    /* Selling prices include VAT, or have it added on top; null keeps the current rule. */
    Boolean pricesIncludeTax,
    /* PREMIUM or CLASSIC; null keeps the current look. */
    String look) {}
