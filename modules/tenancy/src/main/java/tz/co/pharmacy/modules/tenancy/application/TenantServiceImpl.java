package tz.co.pharmacy.modules.tenancy.application;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.tenancy.domain.Branch;
import tz.co.pharmacy.modules.tenancy.domain.Tenant;
import tz.co.pharmacy.modules.tenancy.domain.TenantSettings;
import tz.co.pharmacy.modules.tenancy.events.TenantRegistered;
import tz.co.pharmacy.modules.tenancy.persistence.BranchRepository;
import tz.co.pharmacy.modules.tenancy.persistence.SettingsRepository;
import tz.co.pharmacy.modules.tenancy.persistence.TenantRepository;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.ChangeTenantCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlatformCounts;
import tz.co.pharmacy.modules.tenancy.ports.inbound.RegisterTenantCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.modules.tenancy.ports.outbound.TenantActivity;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;
import tz.co.pharmacy.platform.persistence.TenantSwitch;

@Service
class TenantServiceImpl implements TenantService {

  private final TenantRepository tenants;
  private final BranchRepository branches;
  private final SettingsRepository settings;
  private final TenantSwitch tenantSwitch;
  private final DomainEventPublisher events;
  private final JdbcTemplate jdbc;
  private final TenantActivity activity;
  private final SubscriptionsImpl subscriptions;

  private final java.time.Clock clock;

  TenantServiceImpl(
      TenantRepository tenants,
      BranchRepository branches,
      SettingsRepository settings,
      TenantSwitch tenantSwitch,
      DomainEventPublisher events,
      DataSource dataSource,
      TenantActivity activity,
      SubscriptionsImpl subscriptions,
      java.time.Clock clock) {
    this.clock = clock;
    this.tenants = tenants;
    this.branches = branches;
    this.settings = settings;
    this.tenantSwitch = tenantSwitch;
    this.events = events;
    this.jdbc = new JdbcTemplate(dataSource);
    this.activity = activity;
    this.subscriptions = subscriptions;
  }

  @Override
  @Transactional
  public TenantView register(RegisterTenantCommand command) {
    Checks.required(command, "tenant");
    String code = Checks.code(command.code(), "code");
    String name = Checks.text(command.name(), "name", 120);
    String legalName = Checks.optionalText(command.legalName(), "legalName", 200);
    String country = country(command.country());
    String currency = currency(command.currency());
    String timezone = timezone(command.timezone());
    BranchCommand head = Checks.required(command.headOffice(), "headOffice");
    String branchCode = Checks.code(head.code(), "headOffice.code");
    String branchName = Checks.text(head.name(), "headOffice.name", 120);
    String address = Checks.optionalText(head.address(), "headOffice.address", 300);
    String phone = Checks.optionalText(head.phone(), "headOffice.phone", 40);
    String licence = Checks.optionalText(head.licenceNumber(), "headOffice.licenceNumber", 60);
    if (tenants.existsByCode(code)) {
      throw Refusal.conflict("duplicate_code", "A tenant with code " + code + " exists.");
    }
    Tenant tenant = tenants.save(new Tenant(code, name, legalName, country, currency, timezone));
    subscriptions.startTrial(tenant.getUid());
    tenantSwitch.within(
        tenant.getUid(),
        () -> {
          branches.save(new Branch(branchCode, branchName, address, phone, licence, true));
          settings.save(TenantSettings.defaults());
          events.publish(
              "tenant.registered",
              metadata -> new TenantRegistered(metadata, tenant.getUid(), code, name));
          return null;
        });
    return view(tenant, 1);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TenantView> find(UUID tenantUid) {
    if (tenantUid == null) {
      return Optional.empty();
    }
    return tenants.findByUid(tenantUid).map(this::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<TenantView> list(String q, PageQuery query) {
    return Keyset.page(
        tenants, Specs.containsAny(q, "code", "name", "legalName"), query, this::view);
  }

  @Override
  @Transactional
  public TenantView change(UUID tenantUid, ChangeTenantCommand command) {
    Checks.required(command, "tenant");
    Tenant tenant = required(tenantUid);
    String name = Checks.text(command.name(), "name", 120);
    String legalName = Checks.optionalText(command.legalName(), "legalName", 200);
    String country = country(command.country());
    String timezone = timezone(command.timezone());
    String contactName = Checks.optionalText(command.contactName(), "contactName", 120);
    String contactEmail =
        command.contactEmail() == null || command.contactEmail().isBlank()
            ? null
            : Checks.email(command.contactEmail(), "contactEmail");
    String contactPhone = Checks.optionalText(command.contactPhone(), "contactPhone", 40);
    String address = Checks.optionalText(command.address(), "address", 300);
    if (command.currency() != null && !command.currency().isBlank()) {
      String currency = currency(command.currency());
      if (!currency.equals(tenant.getCurrency())) {
        boolean traded = tenantSwitch.within(tenant.getUid(), activity::hasPostedTransactions);
        if (traded) {
          throw Refusal.invalid(
              "currency_locked",
              "The base currency cannot change once the pharmacy has posted transactions.",
              "currency");
        }
        tenant.changeCurrency(currency);
      }
    }
    tenant.change(
        name, legalName, country, timezone, contactName, contactEmail, contactPhone, address);
    if (command.directUserCreation() != null) {
      tenant.allowDirectUserCreation(command.directUserCreation());
    }
    tenants.flush();
    return view(tenant);
  }

  @Override
  @Transactional
  public TenantView suspend(
      UUID tenantUid,
      tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind kind,
      String note) {
    String why = Checks.text(note, "reason", 500);
    Tenant tenant = required(tenantUid);
    tenant.suspend(
        kind == null ? tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind.OTHER : kind,
        why);
    return view(tenant);
  }

  @Override
  @Transactional
  public TenantView reactivate(UUID tenantUid) {
    Tenant tenant = required(tenantUid);
    if (tenant.getStatus() != TenantStatus.SUSPENDED) {
      throw Refusal.invalid(
          "not_suspended", "Only a suspended pharmacy can be reactivated.", "status");
    }
    tenant.reactivate();
    return view(tenant);
  }

  @Override
  @Transactional
  public TenantView close(UUID tenantUid, String reason, int coolingOffDays) {
    String why = Checks.text(reason, "reason", 500);
    Tenant tenant = required(tenantUid);
    if (tenant.getStatus() == TenantStatus.CLOSED || tenant.getStatus() == TenantStatus.ERASED) {
      throw Refusal.invalid("already_closed", "The pharmacy is already closed.", "status");
    }
    java.time.Instant now = clock.instant();
    tenant.close(why, now, now.plus(java.time.Duration.ofDays(coolingOffDays)));
    return view(tenant);
  }

  @Override
  @Transactional
  public TenantView reopen(UUID tenantUid) {
    Tenant tenant = required(tenantUid);
    if (tenant.getStatus() != TenantStatus.CLOSED) {
      throw Refusal.invalid("not_closed", "Only a closed pharmacy can be reopened.", "status");
    }
    tenant.reopen();
    return view(tenant);
  }

  @Override
  @Transactional
  public TenantView legalHold(UUID tenantUid, String reason) {
    String why = Checks.text(reason, "reason", 500);
    Tenant tenant = required(tenantUid);
    if (tenant.getStatus() == TenantStatus.ERASED) {
      throw Refusal.invalid("erased", "The pharmacy's data is already erased.", "status");
    }
    tenant.placeLegalHold(why);
    return view(tenant);
  }

  @Override
  @Transactional
  public TenantView liftLegalHold(UUID tenantUid) {
    Tenant tenant = required(tenantUid);
    tenant.liftLegalHold();
    return view(tenant);
  }

  @Override
  @Transactional(readOnly = true)
  public PlatformCounts counts() {
    return new PlatformCounts(
        tenants.count(),
        tenants.countByStatus(TenantStatus.ACTIVE),
        tenants.countByStatus(TenantStatus.SUSPENDED));
  }

  @Override
  @Transactional(readOnly = true)
  public long branchTotal() {
    Long total = jdbc.queryForObject("select tenancy.branch_total()", Long.class);
    return total == null ? 0 : total;
  }

  private Tenant required(UUID tenantUid) {
    return tenants
        .findByUid(tenantUid)
        .orElseThrow(() -> Refusal.notFound("tenant_not_found", "No such tenant."));
  }

  private TenantView view(Tenant tenant) {
    Integer count =
        jdbc.queryForObject("select tenancy.branch_count(?)", Integer.class, tenant.getUid());
    return view(tenant, count == null ? 0 : count);
  }

  private static TenantView view(Tenant tenant, int branchCount) {
    return new TenantView(
        tenant.getUid(),
        tenant.getCode(),
        tenant.getName(),
        tenant.getLegalName(),
        tenant.getCountry(),
        tenant.getCurrency(),
        tenant.getTimezone(),
        tenant.getStatus(),
        tenant.getStatusReason(),
        branchCount,
        tenant.getCreatedAt(),
        tenant.getContactName(),
        tenant.getContactEmail(),
        tenant.getContactPhone(),
        tenant.getAddress(),
        tenant.isDirectUserCreation(),
        tenant.getSuspensionKind(),
        tenant.isLegalHold(),
        tenant.getLegalHoldReason(),
        tenant.getClosedAt(),
        tenant.getEraseAfter(),
        tenant.getErasedAt());
  }

  private static String country(String value) {
    String country = Checks.text(value, "country", 2).toUpperCase();
    if (!country.matches("[A-Z]{2}")) {
      throw Refusal.invalid(
          "invalid_country", "country must be an ISO 3166 alpha-2 code.", "country");
    }
    return country;
  }

  private static String currency(String value) {
    String currency = Checks.text(value, "currency", 3).toUpperCase();
    try {
      if (!currency.matches("[A-Z]{3}")) {
        throw new IllegalArgumentException(currency);
      }
      Currency.getInstance(currency);
      return currency;
    } catch (IllegalArgumentException unknown) {
      throw Refusal.invalid("invalid_currency", "currency must be an ISO 4217 code.", "currency");
    }
  }

  private static String timezone(String value) {
    String timezone = Checks.text(value, "timezone", 64);
    try {
      return ZoneId.of(timezone).getId();
    } catch (DateTimeException unknown) {
      throw Refusal.invalid(
          "invalid_timezone",
          "timezone must be an IANA zone such as Africa/Dar_es_Salaam.",
          "timezone");
    }
  }
}
