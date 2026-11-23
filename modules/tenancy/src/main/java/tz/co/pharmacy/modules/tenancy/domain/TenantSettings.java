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

  /** How the web app looks for everyone in the pharmacy: {@code PREMIUM} or {@code CLASSIC}. */
  private String look;

  /** Minutes without use after which a person is signed out (5 to 60). */
  private int idleMinutes;

  /** Everyone who works here needs a second factor (an authenticator app or a passkey). */
  private boolean requireTwoFactor;

  /**
   * Above this amount (base currency) a refund, a stock write-off, a voucher or a shift's cash
   * difference needs a manager's approval (the owner's rule, default TZS 50,000).
   */
  private BigDecimal approvalLimit;

  /** Days after a sale its goods may come back without a manager's approval. */
  private int returnWindowDays;

  /** A cashier must open a till shift before selling or refunding. */
  private boolean requireOpenShift;

  /** The looks a pharmacy can choose. */
  public static final java.util.List<String> LOOKS = java.util.List.of("PREMIUM", "CLASSIC");

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

  /** A known look (see {@link #LOOKS}); null keeps the current one. */
  public void changeLook(String look) {
    if (look != null) {
      this.look = look;
    }
  }

  /** Null keeps the current limit; the service checks the range. */
  public void changeIdleMinutes(Integer idleMinutes) {
    if (idleMinutes != null) {
      this.idleMinutes = idleMinutes;
    }
  }

  /** Null keeps the current value of each. */
  public void changeControls(
      BigDecimal approvalLimit, Integer returnWindowDays, Boolean requireOpenShift) {
    if (approvalLimit != null) {
      this.approvalLimit = approvalLimit;
    }
    if (returnWindowDays != null) {
      this.returnWindowDays = returnWindowDays;
    }
    if (requireOpenShift != null) {
      this.requireOpenShift = requireOpenShift;
    }
  }

  public void changeRequireTwoFactor(Boolean requireTwoFactor) {
    if (requireTwoFactor != null) {
      this.requireTwoFactor = requireTwoFactor;
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
    settings.look = "PREMIUM";
    settings.idleMinutes = 15;
    settings.approvalLimit = new BigDecimal("50000.00");
    settings.returnWindowDays = 7;
    // A new pharmacy ties cash to a cashier from its first sale (owner's decision, 23 Nov 2026).
    settings.requireOpenShift = true;
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
