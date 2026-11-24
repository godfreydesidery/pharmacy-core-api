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
    Boolean requireOpenShift,
    /* Registered for VAT: false charges no VAT on any sale. Null keeps it. */
    Boolean vatRegistered,
    /* The TIN (9 digits; dashes and spaces are dropped); blank clears it, null keeps it. */
    String tin,
    /* The VRN; blank clears it, null keeps it. */
    String vrn,
    /* FRD-19: send the daily digest of sensitive actions; null keeps it. */
    Boolean sensitiveDigest,
    /* Shared counter PCs (WEB-5); null keeps it. */
    Boolean sharedCounterPcs,
    /* The expiry write-down on or off, and its bands (1 to 5, days ascending); null keeps each. */
    Boolean expiryProvisionEnabled,
    java.util.List<ProvisionBand> expiryProvisionBands,
    /* AUTOMATIC or FIXED; null keeps it. */
    String vatApportionmentMethod,
    /* The FIXED recoverable percentage, 0 to 100; null keeps it. */
    BigDecimal vatFixedRecoverablePercent,
    /* The financial year's last month, 1 to 12; null keeps it. */
    Integer vatYearEndMonth,
    /* Round the automatic ratio up to a whole percent; null keeps it. */
    Boolean vatRatioRoundUp) {

  /**
   * The command of the eighteen settings that came before the digest of sensitive actions and the
   * owner's decisions of 24 Nov.
   */
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
      Boolean requireTwoFactor,
      BigDecimal approvalLimit,
      Integer returnWindowDays,
      Boolean requireOpenShift,
      Boolean vatRegistered,
      String tin,
      String vrn) {
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
        approvalLimit,
        returnWindowDays,
        requireOpenShift,
        vatRegistered,
        tin,
        vrn,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** The command of the fifteen settings that came before VAT registration. */
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
      Boolean requireTwoFactor,
      BigDecimal approvalLimit,
      Integer returnWindowDays,
      Boolean requireOpenShift) {
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
        approvalLimit,
        returnWindowDays,
        requireOpenShift,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

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
