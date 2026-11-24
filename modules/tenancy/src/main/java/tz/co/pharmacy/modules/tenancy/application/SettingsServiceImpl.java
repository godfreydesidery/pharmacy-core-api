package tz.co.pharmacy.modules.tenancy.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tz.co.pharmacy.modules.tenancy.domain.Tenant;
import tz.co.pharmacy.modules.tenancy.domain.TenantSettings;
import tz.co.pharmacy.modules.tenancy.persistence.SettingsRepository;
import tz.co.pharmacy.modules.tenancy.persistence.TenantRepository;
import tz.co.pharmacy.modules.tenancy.ports.inbound.ChangeSettingsCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;

@Service
class SettingsServiceImpl implements SettingsService {

  /** The inactivity limits a pharmacy may choose, in minutes. */
  static final int MIN_IDLE_MINUTES = 5;

  static final int MAX_IDLE_MINUTES = 60;

  private final SettingsRepository settings;
  private final TenantRepository tenants;
  private final tz.co.pharmacy.platform.bus.DomainEventPublisher events;

  SettingsServiceImpl(
      SettingsRepository settings,
      TenantRepository tenants,
      tz.co.pharmacy.platform.bus.DomainEventPublisher events) {
    this.settings = settings;
    this.tenants = tenants;
    this.events = events;
  }

  /**
   * A Tanzanian TIN: 9 digits, often written 123-456-789 (TRA; verify for other countries). Spaces
   * and dashes are dropped before the check.
   */
  static final java.util.regex.Pattern TIN = java.util.regex.Pattern.compile("^[0-9]{9}$");

  /**
   * A VRN, checked loosely: 8 to 15 letters, digits and dashes, starting with a digit, such as
   * 40-012345-A (TRA's printed form; verify). Upper-cased, spaces dropped.
   */
  static final java.util.regex.Pattern VRN =
      java.util.regex.Pattern.compile("^[0-9][0-9A-Z-]{7,14}$");

  /** The TIN as kept (digits only), blank to clear, null to keep; 422 invalid_tin otherwise. */
  static String tin(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String digits = value.replaceAll("[\\s-]", "");
    if (!TIN.matcher(digits).matches()) {
      throw Refusal.invalid("invalid_tin", "A TIN is 9 digits, such as 123-456-789.", "tin");
    }
    return digits;
  }

  /** The VRN as kept (upper case), blank to clear, null to keep; 422 invalid_vrn otherwise. */
  static String vrn(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String code = value.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    if (!VRN.matcher(code).matches()) {
      throw Refusal.invalid(
          "invalid_vrn",
          "A VRN is 8 to 15 letters, digits and dashes, such as 40-012345-A.",
          "vrn");
    }
    return code;
  }

  /**
   * Read once per transaction and kept for the rest of it (PERF-10): a checkout asks for the
   * pharmacy's settings at the till, in pricing, in the sale and in its shift check, and each ask
   * used to read the tenant and settings rows again. The copy lives in the transaction's resources
   * (one thread, one transaction, one tenant), never across transactions or requests; {@link
   * #change} drops it.
   */
  @Override
  @Transactional(readOnly = true)
  public SettingsView current() {
    String key = MEMO + TenantContext.requireTenant();
    if (TransactionSynchronizationManager.getResource(key) instanceof SettingsView kept) {
      return kept;
    }
    SettingsView view = view(tenant(), row());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.bindResource(key, view);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              TransactionSynchronizationManager.unbindResourceIfPossible(key);
            }
          });
    }
    return view;
  }

  /** The transaction resource under which {@link #current} keeps a tenant's settings. */
  private static final String MEMO = SettingsServiceImpl.class.getName() + ".current:";

  @Override
  @Transactional
  public SettingsView change(ChangeSettingsCommand command) {
    Checks.required(command, "settings");
    TransactionSynchronizationManager.unbindResourceIfPossible(
        MEMO + TenantContext.requireTenant());
    TenantSettings row = row();
    java.math.BigDecimal rateBefore = row.getDefaultTaxRatePercent();
    String prefix =
        command.invoicePrefix() == null
            ? null
            : Checks.code(command.invoicePrefix(), "invoicePrefix");
    if (command.expiryAlertDays() != null
        && (command.expiryAlertDays() < 1 || command.expiryAlertDays() > 730)) {
      throw Refusal.invalid(
          "invalid_expiry_alert_days",
          "expiryAlertDays must be between 1 and 730.",
          "expiryAlertDays");
    }
    if (command.idleMinutes() != null
        && (command.idleMinutes() < MIN_IDLE_MINUTES || command.idleMinutes() > MAX_IDLE_MINUTES)) {
      throw Refusal.invalid(
              "invalid_idle_minutes",
              "idleMinutes must be between " + MIN_IDLE_MINUTES + " and " + MAX_IDLE_MINUTES + ".",
              "idleMinutes")
          .with("min", MIN_IDLE_MINUTES)
          .with("max", MAX_IDLE_MINUTES);
    }
    String footer =
        command.receiptFooter() == null || command.receiptFooter().isBlank()
            ? command.receiptFooter()
            : Checks.text(command.receiptFooter(), "receiptFooter", 500);
    row.change(
        command.defaultTaxRatePercent() == null
            ? null
            : Checks.percent(command.defaultTaxRatePercent(), "defaultTaxRatePercent"),
        prefix,
        footer,
        command.maxDiscountPercent() == null
            ? null
            : Checks.percent(command.maxDiscountPercent(), "maxDiscountPercent"),
        command.expiryAlertDays(),
        command.lowStockAlerts());
    row.changeVat(command.vatRegistered(), tin(command.tin()), vrn(command.vrn()));
    row.changeSmsReceipts(command.smsReceipts());
    row.changePricesIncludeTax(command.pricesIncludeTax());
    row.changeLook(look(command.look()));
    row.changeIdleMinutes(command.idleMinutes());
    row.changeRequireTwoFactor(command.requireTwoFactor());
    if (command.approvalLimit() != null && command.approvalLimit().signum() < 0) {
      throw Refusal.invalid("invalid_amount", "approvalLimit cannot be negative.", "approvalLimit");
    }
    if (command.returnWindowDays() != null
        && (command.returnWindowDays() < 0 || command.returnWindowDays() > 365)) {
      throw Refusal.invalid(
          "invalid_return_window",
          "returnWindowDays must be between 0 and 365.",
          "returnWindowDays");
    }
    row.changeControls(
        command.approvalLimit() == null
            ? null
            : tz.co.pharmacy.platform.kernel.Amounts.money(command.approvalLimit()),
        command.returnWindowDays(),
        command.requireOpenShift());
    Tenant tenant = tenant();
    row.changeAcceptedCurrencies(foreignCurrencies(command.acceptedCurrencies(), tenant));
    java.math.BigDecimal rate = row.getDefaultTaxRatePercent();
    if (rate != null && (rateBefore == null || rate.compareTo(rateBefore) != 0)) {
      // The catalogue sells every STANDARD product at the new rate (after this commits).
      events.publish(
          "settings.standard_vat_rate_changed",
          metadata ->
              new tz.co.pharmacy.modules.tenancy.events.StandardVatRateChanged(metadata, rate));
    }
    // Flushed, so the view carries the version this change gave the row (its new ETag).
    settings.flush();
    return view(tenant, row);
  }

  /**
   * The accepted currencies without the base: valid ISO 4217 codes, upper case, sorted and unique;
   * null keeps the current ones.
   */
  private static java.util.List<String> foreignCurrencies(
      java.util.List<String> accepted, Tenant tenant) {
    if (accepted == null) {
      return null;
    }
    java.util.TreeSet<String> codes = new java.util.TreeSet<>();
    for (int i = 0; i < accepted.size(); i++) {
      String field = "acceptedCurrencies[" + i + "]";
      String code = Checks.text(accepted.get(i), field, 3).toUpperCase(java.util.Locale.ROOT);
      try {
        java.util.Currency.getInstance(code);
      } catch (IllegalArgumentException unknown) {
        throw Refusal.invalid(
            "invalid_currency", code + " is not an ISO 4217 currency code.", field);
      }
      codes.add(code);
    }
    codes.remove(tenant.getCurrency());
    return java.util.List.copyOf(codes);
  }

  /** The look upper-cased and known; null keeps the current one. */
  private static String look(String look) {
    if (look == null) {
      return null;
    }
    String code = look.trim().toUpperCase(java.util.Locale.ROOT);
    if (!TenantSettings.LOOKS.contains(code)) {
      throw Refusal.invalid(
          "invalid_look", "The look must be one of " + TenantSettings.LOOKS + ".", "look");
    }
    return code;
  }

  /** The base currency first, then the accepted foreign ones. */
  private static java.util.List<String> accepted(Tenant tenant, TenantSettings row) {
    java.util.List<String> all = new java.util.ArrayList<>();
    all.add(tenant.getCurrency());
    all.addAll(row.foreignCurrencies());
    return java.util.List.copyOf(all);
  }

  private Tenant tenant() {
    return tenants
        .findByUid(TenantContext.requireTenant())
        .orElseThrow(() -> Refusal.notFound("tenant_not_found", "No such tenant."));
  }

  private TenantSettings row() {
    return settings
        .findFirstByOrderByIdAsc()
        .orElseThrow(() -> Refusal.notFound("settings_not_found", "The tenant has no settings."));
  }

  private static SettingsView view(Tenant tenant, TenantSettings row) {
    return new SettingsView(
        tenant.getCurrency(),
        tenant.getTimezone(),
        row.getDefaultTaxRatePercent(),
        row.getInvoicePrefix(),
        row.getReceiptFooter(),
        row.getMaxDiscountPercent(),
        row.getExpiryAlertDays(),
        row.isLowStockAlerts(),
        row.isSmsReceipts(),
        accepted(tenant, row),
        row.isPricesIncludeTax(),
        row.getLook(),
        row.getIdleMinutes(),
        row.isRequireTwoFactor(),
        row.getApprovalLimit(),
        row.getReturnWindowDays(),
        row.isRequireOpenShift(),
        row.isVatRegistered(),
        row.getTin(),
        row.getVrn(),
        row.getVersion());
  }
}
