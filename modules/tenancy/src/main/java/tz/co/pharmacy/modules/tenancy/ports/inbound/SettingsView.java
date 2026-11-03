package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.math.BigDecimal;

public record SettingsView(
    String currency,
    String timezone,
    BigDecimal defaultTaxRatePercent,
    String invoicePrefix,
    String receiptFooter,
    BigDecimal maxDiscountPercent,
    int expiryAlertDays,
    boolean lowStockAlerts,
    boolean smsReceipts,
    /* The base currency first, then the accepted foreign currencies. */
    java.util.List<String> acceptedCurrencies) {}
