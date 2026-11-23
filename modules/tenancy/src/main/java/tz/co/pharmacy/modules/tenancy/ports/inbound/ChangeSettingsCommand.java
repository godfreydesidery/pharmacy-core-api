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
    String look,
    /* Minutes without use that sign a person out, 5 to 60; null keeps the current limit. */
    Integer idleMinutes,
    /* Require a second factor of everyone who works here; null keeps the current rule. */
    Boolean requireTwoFactor,
    /* Above this amount a refund, write-off, voucher or shift difference needs a manager. */
    BigDecimal approvalLimit,
    /* Days a sale's goods may come back without a manager, 0 to 365. */
    Integer returnWindowDays,
    /* A cashier must open a shift before selling or refunding. */
    Boolean requireOpenShift) {

  /** The command of the twelve settings that came before the controls. */
  public ChangeSettingsCommand(
      BigDecimal defaultTaxRatePercent,
      String invoicePrefix,
      String receiptFooter,
      BigDecimal maxDiscountPercent,
      Integer expiryAlertDays,
      Boolean lowStockAlerts,
      Boolean smsReceipts,
      java.util.List<String> acceptedCurrencies,
      Boolean pricesIncludeTax,
      String look,
      Integer idleMinutes,
      Boolean requireTwoFactor) {
    this(
        defaultTaxRatePercent,
        invoicePrefix,
        receiptFooter,
        maxDiscountPercent,
        expiryAlertDays,
        lowStockAlerts,
        smsReceipts,
        acceptedCurrencies,
        pricesIncludeTax,
        look,
        idleMinutes,
        requireTwoFactor,
        null,
        null,
        null);
  }
}
