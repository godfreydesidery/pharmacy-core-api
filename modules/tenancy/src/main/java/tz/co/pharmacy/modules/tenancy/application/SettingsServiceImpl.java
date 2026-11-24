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
import tz.co.pharmacy.modules.tenancy.ports.inbound.ProvisionBand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.VatApportionmentMethod;
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
  private final tz.co.pharmacy.platform.persistence.audit.AuditLog audit;

  SettingsServiceImpl(
      SettingsRepository settings,
      TenantRepository tenants,
      tz.co.pharmacy.platform.bus.DomainEventPublisher events,
      tz.co.pharmacy.platform.persistence.audit.AuditLog audit) {
    this.audit = audit;
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
    SettingsView before = view(tenant(), row);
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
    row.changeDecisions(
        command.sharedCounterPcs(),
        command.expiryProvisionEnabled(),
        command.expiryProvisionBands() == null ? null : bands(command.expiryProvisionBands()),
        method(command.vatApportionmentMethod()),
        recoverablePercent(command.vatFixedRecoverablePercent()),
        yearEndMonth(command.vatYearEndMonth()),
        command.vatRatioRoundUp());
    if ("FIXED".equals(row.getVatApportionmentMethod())
        && row.getVatFixedRecoverablePercent() == null) {
      throw Refusal.invalid(
          "recoverable_percent_required",
          "The fixed method needs the recoverable percentage agreed with TRA or the accountant.",
          "vatFixedRecoverablePercent");
    }
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
    SettingsView after = view(tenant, row);
    audited(before, after);
    return after;
  }

  /**
   * Records what changed, old and new, field by field (the owner asked that the write-down and VAT
   * rules be audited; every setting is, at no extra cost). Nothing changed records nothing.
   */
  private void audited(SettingsView before, SettingsView after) {
    java.util.Map<String, Object> old = new java.util.LinkedHashMap<>();
    java.util.Map<String, Object> now = new java.util.LinkedHashMap<>();
    for (var component : SettingsView.class.getRecordComponents()) {
      if (component.getName().equals("version")) {
        continue;
      }
      try {
        Object a = component.getAccessor().invoke(before);
        Object b = component.getAccessor().invoke(after);
        if (!same(a, b)) {
          old.put(component.getName(), printable(a));
          now.put(component.getName(), printable(b));
        }
      } catch (ReflectiveOperationException unreadable) {
        throw new IllegalStateException(unreadable);
      }
    }
    if (!now.isEmpty()) {
      audit.record("SETTINGS_CHANGED", "TenantSettings", TenantContext.requireTenant(), old, now);
    }
  }

  private static boolean same(Object a, Object b) {
    if (a instanceof java.math.BigDecimal x && b instanceof java.math.BigDecimal y) {
      return x.compareTo(y) == 0;
    }
    return java.util.Objects.equals(a, b);
  }

  private static Object printable(Object value) {
    if (value == null) {
      return "";
    }
    return value instanceof Boolean || value instanceof Number ? value : value.toString();
  }

  /**
   * The bands as kept ({@code days:percent;...}), refused with 422 invalid_provision_bands unless
   * there are 1 to 5, days run 0 to 730 strictly upwards and the percentages, 0 to 100, do not rise
   * as the days do (stock further from expiry is never written down more).
   */
  static String bands(java.util.List<ProvisionBand> bands) {
    if (bands.isEmpty() || bands.size() > TenantSettings.MAX_PROVISION_BANDS) {
      throw badBands("Give 1 to " + TenantSettings.MAX_PROVISION_BANDS + " bands.");
    }
    StringBuilder kept = new StringBuilder();
    int lastDays = -1;
    java.math.BigDecimal lastPercent = null;
    for (ProvisionBand band : bands) {
      if (band == null || band.percent() == null) {
        throw badBands("Each band needs its days to expiry and a percentage.");
      }
      java.math.BigDecimal percent = band.percent().setScale(2, java.math.RoundingMode.HALF_UP);
      if (band.daysToExpiry() < 0
          || band.daysToExpiry() > TenantSettings.MAX_PROVISION_DAYS
          || band.daysToExpiry() <= lastDays) {
        throw badBands(
            "Days to expiry run from 0 (already expired) to "
                + TenantSettings.MAX_PROVISION_DAYS
                + ", each band later than the one before.");
      }
      if (percent.signum() < 0
          || percent.compareTo(java.math.BigDecimal.valueOf(100)) > 0
          || (lastPercent != null && percent.compareTo(lastPercent) > 0)) {
        throw badBands(
            "Percentages run from 0 to 100 and do not rise for stock further from expiry.");
      }
      if (!kept.isEmpty()) {
        kept.append(';');
      }
      kept.append(band.daysToExpiry())
          .append(':')
          .append(percent.stripTrailingZeros().toPlainString());
      lastDays = band.daysToExpiry();
      lastPercent = percent;
    }
    return kept.toString();
  }

  private static Refusal badBands(String detail) {
    return Refusal.invalid("invalid_provision_bands", detail, "expiryProvisionBands")
        .with("maxBands", TenantSettings.MAX_PROVISION_BANDS)
        .with("maxDays", TenantSettings.MAX_PROVISION_DAYS);
  }

  /** The kept bands read back. */
  static java.util.List<ProvisionBand> bands(String kept) {
    if (kept == null || kept.isBlank()) {
      return java.util.List.of();
    }
    java.util.List<ProvisionBand> bands = new java.util.ArrayList<>();
    for (String pair : kept.split(";")) {
      String[] parts = pair.split(":");
      bands.add(
          new ProvisionBand(
              Integer.parseInt(parts[0]),
              new java.math.BigDecimal(parts[1]).setScale(2, java.math.RoundingMode.HALF_UP)));
    }
    return java.util.List.copyOf(bands);
  }

  private static String method(String method) {
    if (method == null) {
      return null;
    }
    try {
      return VatApportionmentMethod.valueOf(method.trim().toUpperCase(java.util.Locale.ROOT))
          .name();
    } catch (IllegalArgumentException unknown) {
      throw Refusal.invalid(
          "invalid_apportionment_method",
          "The VAT apportionment method is AUTOMATIC or FIXED.",
          "vatApportionmentMethod");
    }
  }

  private static java.math.BigDecimal recoverablePercent(java.math.BigDecimal percent) {
    if (percent == null) {
      return null;
    }
    if (percent.signum() < 0 || percent.compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
      throw Refusal.invalid(
              "invalid_recoverable_percent",
              "The recoverable percentage is between 0 and 100.",
              "vatFixedRecoverablePercent")
          .with("min", 0)
          .with("max", 100);
    }
    return percent.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static Integer yearEndMonth(Integer month) {
    if (month != null && (month < 1 || month > 12)) {
      throw Refusal.invalid(
          "invalid_year_end_month",
          "The financial year ends in a month from 1 (January) to 12 (December).",
          "vatYearEndMonth");
    }
    return month;
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
        row.isSharedCounterPcs(),
        row.isExpiryProvisionEnabled(),
        bands(row.getExpiryProvisionBands()),
        VatApportionmentMethod.valueOf(row.getVatApportionmentMethod()),
        row.getVatFixedRecoverablePercent(),
        row.getVatYearEndMonth(),
        row.isVatRatioRoundUp(),
        row.getVersion());
  }
}
