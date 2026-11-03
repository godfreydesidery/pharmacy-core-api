package tz.co.pharmacy.platform.composition;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.inventory.ports.inbound.ExpiringBatchView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.modules.notifications.ports.outbound.NotificationDirectory;
import tz.co.pharmacy.modules.reporting.ports.inbound.DashboardView;
import tz.co.pharmacy.modules.reporting.ports.inbound.Reports;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The daily digests, at each pharmacy's own local time: at 07:00 the batches expiring within its
 * alert window, to the people who manage stock; at 18:00 the day's sales, to those who read
 * reports. The job wakes every 15 minutes; a digest's dedup key (its local date) sends it once.
 */
@Component
class NotificationSchedules {

  private static final Logger log = LoggerFactory.getLogger(NotificationSchedules.class);
  private static final String SERVICE = "notification-digests";
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  private final TenantService tenants;
  private final SettingsService settings;
  private final StockQueries stock;
  private final Reports reports;
  private final NotificationDirectory directory;
  private final Notifier notifier;

  NotificationSchedules(
      TenantService tenants,
      SettingsService settings,
      StockQueries stock,
      Reports reports,
      NotificationDirectory directory,
      Notifier notifier) {
    this.tenants = tenants;
    this.settings = settings;
    this.stock = stock;
    this.reports = reports;
    this.directory = directory;
    this.notifier = notifier;
  }

  @Scheduled(cron = "${pharmacy.notifications.digests.cron:0 */15 * * * *}")
  void digests() {
    for (TenantView tenant : activeTenants()) {
      ZonedDateTime local = ZonedDateTime.now(zone(tenant));
      try {
        if (local.getHour() == 7) {
          inTenant(tenant.uid(), () -> expiryDigest(local.toLocalDate()));
        } else if (local.getHour() == 18) {
          inTenant(tenant.uid(), () -> dailySummary(tenant, local.toLocalDate()));
        }
      } catch (RuntimeException failure) {
        log.warn("Digest for tenant {} failed: {}", tenant.code(), failure.getMessage());
      }
    }
  }

  void expiryDigest(LocalDate today) {
    SettingsView current = settings.current();
    Page<ExpiringBatchView> expiring =
        stock.expiring(null, current.expiryAlertDays(), PageQuery.first(20));
    if (expiring.items().isEmpty()) {
      return;
    }
    String items =
        expiring.items().stream()
            .map(
                batch ->
                    "• "
                        + batch.productName()
                        + " ("
                        + batch.batchNumber()
                        + ") – "
                        + DAY.format(batch.expiryDate())
                        + (batch.daysLeft() < 0
                            ? " (expired)"
                            : " (" + batch.daysLeft() + " days)"))
            .collect(Collectors.joining("\n"));
    send(
        TemplateKeys.STOCK_EXPIRING,
        "inventory.adjust",
        Map.of(
            "count",
            expiring.items().size(),
            "days",
            current.expiryAlertDays(),
            "items",
            items,
            "severity",
            "warning",
            "link",
            "/inventory/expiry"),
        "stock.expiring:" + today);
  }

  void dailySummary(TenantView tenant, LocalDate today) {
    DashboardView summary = reports.dashboard(1);
    if (summary.transactions().value().signum() == 0) {
      return;
    }
    String top =
        summary.topProducts().isEmpty() ? "—" : summary.topProducts().getFirst().productName();
    send(
        TemplateKeys.DAILY_SUMMARY,
        "reports.view",
        Map.of(
            "pharmacyName", tenant.name(),
            "sales", NotificationReactions.money(summary.currency(), summary.sales().value()),
            "transactions", summary.transactions().value().toBigInteger(),
            "topProduct", top,
            "link", "/dashboard"),
        "daily.summary:" + today);
  }

  private void send(String key, String permission, Map<String, Object> params, String dedupKey) {
    for (Recipient recipient : directory.usersWithPermission(permission, null)) {
      notifier.send(
          new NotificationRequest(
              key, recipient, EnumSet.of(Channel.IN_APP, Channel.EMAIL), params, dedupKey));
    }
  }

  private List<TenantView> activeTenants() {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, null, null),
        () -> {
          List<TenantView> all = new java.util.ArrayList<>();
          PageQuery query = PageQuery.first(100);
          while (true) {
            Page<TenantView> page = tenants.list(null, query);
            page.items().stream().filter(t -> t.status() == TenantStatus.ACTIVE).forEach(all::add);
            if (page.nextCursor() == null) {
              return all;
            }
            query = new PageQuery(page.nextCursor(), 100);
          }
        });
  }

  private static ZoneId zone(TenantView tenant) {
    try {
      return ZoneId.of(tenant.timezone());
    } catch (RuntimeException invalid) {
      return ZoneId.of("UTC");
    }
  }

  private static void inTenant(UUID tenantUid, Runnable work) {
    ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, null, tenantUid),
        () ->
            TenantContext.callAs(
                tenantUid,
                () -> {
                  work.run();
                  return null;
                }));
  }
}
