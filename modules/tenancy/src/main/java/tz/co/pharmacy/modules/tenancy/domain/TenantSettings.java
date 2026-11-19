package tz.co.pharmacy.modules.tenancy.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** The tenant's own settings: one row per tenant. */
@Getter
@Entity
@Table(schema = "tenancy", name = "settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantSettings extends TenantEntity {

  private BigDecimal defaultTaxRatePercent;
  private String invoicePrefix;
  private String receiptFooter;
  private BigDecimal maxDiscountPercent;
  private int expiryAlertDays;
  private boolean lowStockAlerts;

  /** Whether a sale to a customer with a phone number sends the receipt by SMS. */
  private boolean smsReceipts;

  /**
   * Whether selling prices already include VAT (the tax is inside the price) rather than exclude it
   * (the tax is added on top at the till).
   */
  private boolean pricesIncludeTax;

  /**
   * The foreign currencies the tenant accepts besides its base, as ISO codes joined by commas, such
   * as {@code USD,KES}; null or empty for the base only.
   */
  private String acceptedCurrencies;

  /** The accepted foreign currencies, without the base. */
  public java.util.List<String> foreignCurrencies() {
    return acceptedCurrencies == null || acceptedCurrencies.isBlank()
        ? java.util.List.of()
        : java.util.List.of(acceptedCurrencies.split(","));
  }

  /** Replaces the accepted foreign currencies; null keeps them. */
  public void changeAcceptedCurrencies(java.util.List<String> foreignCodes) {
    if (foreignCodes != null) {
      this.acceptedCurrencies = foreignCodes.isEmpty() ? null : String.join(",", foreignCodes);
    }
  }

  public void changePricesIncludeTax(Boolean pricesIncludeTax) {
    if (pricesIncludeTax != null) {
      this.pricesIncludeTax = pricesIncludeTax;
    }
  }

  public void changeSmsReceipts(Boolean smsReceipts) {
    if (smsReceipts != null) {
      this.smsReceipts = smsReceipts;
    }
  }

  /** The settings a new tenant starts with. */
  public static TenantSettings defaults() {
    TenantSettings settings = new TenantSettings();
    settings.defaultTaxRatePercent = BigDecimal.ZERO;
    settings.invoicePrefix = "INV";
    settings.receiptFooter = "Thank you. Get well soon.";
    settings.maxDiscountPercent = BigDecimal.TEN;
    settings.expiryAlertDays = 90;
    settings.lowStockAlerts = true;
    // Shelf prices in Tanzania are shown to the customer with VAT in them.
    settings.pricesIncludeTax = true;
    return settings;
  }

  public void change(
      BigDecimal defaultTaxRatePercent,
      String invoicePrefix,
      String receiptFooter,
      BigDecimal maxDiscountPercent,
      Integer expiryAlertDays,
      Boolean lowStockAlerts) {
    if (defaultTaxRatePercent != null) {
      this.defaultTaxRatePercent = defaultTaxRatePercent;
    }
    if (invoicePrefix != null) {
      this.invoicePrefix = invoicePrefix;
    }
    if (receiptFooter != null) {
      this.receiptFooter = receiptFooter.isBlank() ? null : receiptFooter.trim();
    }
    if (maxDiscountPercent != null) {
      this.maxDiscountPercent = maxDiscountPercent;
    }
    if (expiryAlertDays != null) {
      this.expiryAlertDays = expiryAlertDays;
    }
    if (lowStockAlerts != null) {
      this.lowStockAlerts = lowStockAlerts;
    }
  }
}
