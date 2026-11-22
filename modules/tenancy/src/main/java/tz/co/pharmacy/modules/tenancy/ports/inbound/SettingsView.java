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
    java.util.List<String> acceptedCurrencies,
    /* Selling prices include VAT (true) or have it added on top (false). */
    boolean pricesIncludeTax,
    /* How the web app looks for everyone in the pharmacy: PREMIUM or CLASSIC. */
    String look,
    /* Minutes without use that sign a person out of this pharmacy. */
    int idleMinutes,
    /* Everyone who works here needs a second factor (an authenticator app or a passkey). */
    boolean requireTwoFactor) {}
