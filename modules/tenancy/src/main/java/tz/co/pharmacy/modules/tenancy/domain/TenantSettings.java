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

  /**
   * Whether the pharmacy is registered for VAT with TRA (STD-2). A pharmacy that is not charges no
   * VAT on any sale and books no input VAT. {@link #getDefaultTaxRatePercent()} is then the
   * standard rate its STANDARD products are sold at once it registers.
   */
  private boolean vatRegistered;

  /** The pharmacy's Taxpayer Identification Number: 9 digits in Tanzania (verify), or null. */
  private String tin;

  /** The pharmacy's VAT Registration Number (TRA), upper case, or null. */
  private String vrn;

  /**
   * Shared counter PCs (WEB-5, owner's decision of 24 Nov 2026): the web keeps the session for the
   * browser's life only, and parked sales end with the pharmacy's business day.
   */
  private boolean sharedCounterPcs;

  /** The monthly expiry write-down (STD-3) is on. */
  private boolean expiryProvisionEnabled;

  /** Its bands as {@code days:percent} pairs joined by {@code ;}, days ascending. */
  private String expiryProvisionBands;

  /** AUTOMATIC or FIXED (STD-2 partial exemption). */
  private String vatApportionmentMethod;

  /** The recoverable percentage under FIXED; null otherwise allowed. */
  private BigDecimal vatFixedRecoverablePercent;

  /** The financial year's last month (1 to 12). */
  private int vatYearEndMonth;

  /** The automatic ratio is rounded up to the next whole percent. */
  private boolean vatRatioRoundUp;

  /** The rule an admin may set: at most five bands, days 0 to 730. */
  public static final int MAX_PROVISION_BANDS = 5;

  public static final int MAX_PROVISION_DAYS = 730;

  /** Null keeps each; the service checks the bands and the method. */
  public void changeDecisions(
      Boolean sharedCounterPcs,
      Boolean expiryProvisionEnabled,
      String expiryProvisionBands,
      String vatApportionmentMethod,
      BigDecimal vatFixedRecoverablePercent,
      Integer vatYearEndMonth,
      Boolean vatRatioRoundUp) {
    if (sharedCounterPcs != null) {
      this.sharedCounterPcs = sharedCounterPcs;
    }
    if (expiryProvisionEnabled != null) {
      this.expiryProvisionEnabled = expiryProvisionEnabled;
    }
    if (expiryProvisionBands != null) {
      this.expiryProvisionBands = expiryProvisionBands;
    }
    if (vatApportionmentMethod != null) {
      this.vatApportionmentMethod = vatApportionmentMethod;
    }
    if (vatFixedRecoverablePercent != null) {
      this.vatFixedRecoverablePercent = vatFixedRecoverablePercent;
    }
    if (vatYearEndMonth != null) {
      this.vatYearEndMonth = vatYearEndMonth;
    }
    if (vatRatioRoundUp != null) {
      this.vatRatioRoundUp = vatRatioRoundUp;
    }
  }

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

  /**
   * The VAT registration: null keeps each; a blank TIN or VRN clears it. The service checks the
   * formats.
   */
  public void changeVat(Boolean vatRegistered, String tin, String vrn) {
    if (vatRegistered != null) {
      this.vatRegistered = vatRegistered;
    }
    if (tin != null) {
      this.tin = tin.isBlank() ? null : tin;
    }
    if (vrn != null) {
      this.vrn = vrn.isBlank() ? null : vrn;
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
    // The standard VAT rate in Tanzania (VAT Act 2014 s.5, verify), for STANDARD products.
    settings.defaultTaxRatePercent = new BigDecimal("18.00");
    // As every pharmacy charged before VAT registration was kept (STD-2): a pharmacy that is not
    // registered switches it off in its settings, and then charges no VAT.
    settings.vatRegistered = true;
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
    // The owner's decisions of 24 Nov 2026: shared PCs off; the expiry write-down on (IAS 2 needs
    // stock at no more than what it will fetch); the taxable share of sales for mixed input VAT.
    settings.sharedCounterPcs = false;
    settings.expiryProvisionEnabled = true;
    settings.expiryProvisionBands = "0:100;30:50;90:25";
    settings.vatApportionmentMethod = "AUTOMATIC";
    settings.vatYearEndMonth = 12;
    settings.vatRatioRoundUp = true;
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
