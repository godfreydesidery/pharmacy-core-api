package tz.co.pharmacy.platform.composition;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.modules.notifications.ports.outbound.NotificationDirectory;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditEntryView;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;

/**
 * The owner's digest of sensitive actions (adversarial review FRD-19). Each morning at 07:00 in the
 * pharmacy's own time zone, its owners and admins (who may read the audit trail, {@code
 * settings.manage}) get, in the app and by email, what happened the day before that a second pair
 * of eyes should see, read from the audit trail:
 *
 * <ul>
 *   <li>refunds and returns (each sale return);
 *   <li>write-offs, adjustments and destructions of stock;
 *   <li>approvals granted, among them deep discounts and promotions, period unlocks, medicine class
 *       changes and credit write-offs, which always need one;
 *   <li>role and permission changes, and changes to who works in the pharmacy;
 *   <li>moves of the books' period lock, vouchers issued, medicine class changes and debts written
 *       off.
 * </ul>
 *
 * <p>A pharmacy turns it off in Settings ({@code sensitiveDigest}; on by default). Nothing to
 * report sends nothing. The job wakes every 15 minutes on one instance ({@link SingleRunner}), and
 * the digest's dedup key (its day) sends it once a day per person and channel, whichever instance
 * runs it. At most {@value #ITEMS} actions are listed; the counts cover them all.
 */
@Component
class SensitiveActionsDigest {

  static final String JOB = "sensitive-digest";

  /** The most actions a digest lists one by one. */
  static final int ITEMS = 30;

  /** The most audit entries one digest reads (a day's sensitive actions are far fewer). */
  static final int READ = 2_000;

  /** The local hour a digest goes out. */
  static final int HOUR = 7;

  private static final Logger log = LoggerFactory.getLogger(SensitiveActionsDigest.class);
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  /** The explicit audit actions a digest reads. */
  static final Set<String> ACTIONS =
      Set.of(
          "APPROVAL_GRANTED",
          "STOCK_ADJUSTED",
          "PERIOD_LOCK_CHANGED",
          "SCHEDULE_CHANGED",
          "MEMBER_LEFT",
          "ROLE_PERMISSIONS_CHANGED");

  /** The records whose changes a digest reads. */
  static final Set<String> ENTITIES =
      Set.of("SaleReturn", "Destruction", "Voucher", "Role", "Membership", "Entry");

  /** One kind of sensitive action, as the digest groups them. */
  enum Kind {
    RETURNS("Refunds and returns"),
    STOCK("Write-offs, adjustments and destructions"),
    APPROVALS("Approvals granted"),
    DISCOUNTS("Deep discounts and promotions approved"),
    ROLES("Role, permission and staff changes"),
    PERIOD("Period lock changes"),
    VOUCHERS("Vouchers issued"),
    SCHEDULE("Medicine class changes"),
    WRITE_OFFS("Debts written off");

    final String title;

    Kind(String title) {
      this.title = title;
    }
  }

  /** One action in the digest. */
  record Item(Kind kind, Instant at, String actor, String what) {}

  private final TenantService tenants;
  private final SettingsService settings;
  private final AuditLog audit;
  private final NotificationDirectory directory;
  private final Notifier notifier;
  private final SingleRunner runner;

  SensitiveActionsDigest(
      TenantService tenants,
      SettingsService settings,
      AuditLog audit,
      NotificationDirectory directory,
      Notifier notifier,
      SingleRunner runner) {
    this.tenants = tenants;
    this.settings = settings;
    this.audit = audit;
    this.directory = directory;
    this.notifier = notifier;
    this.runner = runner;
  }

  @Scheduled(cron = "${pharmacy.notifications.sensitive-digest.cron:0 */15 * * * *}")
  void scheduled() {
    runner.runAlone(JOB, this::round);
  }

  void round(JobRun run) {
    Instant now = Clocks.now();
    int sent = 0;
    for (TenantView tenant : activeTenants()) {
      ZonedDateTime local = now.atZone(zone(tenant));
      if (local.getHour() != HOUR) {
        continue;
      }
      try {
        if (inTenant(tenant.uid(), () -> send(tenant, local.toLocalDate().minusDays(1)))) {
          sent++;
        }
      } catch (RuntimeException failure) {
        log.warn("Sensitive digest for {} failed: {}", tenant.code(), failure.getMessage());
        run.failed(tenant.uid(), failure);
      }
    }
    if (sent > 0) {
      run.note(sent + " digests of sensitive actions sent");
    }
  }

  /**
   * Sends the current pharmacy's digest of {@code day} (its local day) to its owners and admins,
   * unless the pharmacy turned it off or nothing happened. Returns whether it was sent.
   */
  boolean send(TenantView tenant, LocalDate day) {
    if (!settings.current().sensitiveDigest()) {
      return false;
    }
    ZoneId zone = zone(tenant);
    List<Item> items =
        items(
            audit.between(
                day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant(),
                ACTIONS,
                ENTITIES,
                READ));
    if (items.isEmpty()) {
      return false;
    }
    Map<Kind, Integer> counts = new LinkedHashMap<>();
    for (Kind kind : Kind.values()) {
      int count = (int) items.stream().filter(i -> i.kind() == kind).count();
      if (count > 0) {
        counts.put(kind, count);
      }
    }
    StringBuilder summary = new StringBuilder();
    counts.forEach(
        (kind, count) ->
            summary.append("• ").append(kind.title).append(": ").append(count).append('\n'));
    StringBuilder list = new StringBuilder();
    items.stream()
        .limit(ITEMS)
        .forEach(
            item ->
                list.append("• ")
                    .append(TIME.format(item.at().atZone(zone)))
                    .append(" ")
                    .append(item.what())
                    .append(" (")
                    .append(item.actor())
                    .append(")\n"));
    if (items.size() > ITEMS) {
      list.append("… and ").append(items.size() - ITEMS).append(" more in the audit trail.\n");
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("pharmacyName", tenant.name());
    params.put("date", DAY.format(day));
    params.put("count", items.size());
    params.put("summary", summary.toString().strip());
    params.put("items", list.toString().strip());
    params.put("severity", "warning");
    params.put("link", "/admin/audit?from=" + day + "&to=" + day);
    List<Recipient> recipients = directory.usersWithPermission("settings.manage", null);
    for (Recipient recipient : recipients) {
      notifier.send(
          new NotificationRequest(
              TemplateKeys.SENSITIVE_DIGEST,
              recipient,
              EnumSet.of(Channel.IN_APP, Channel.EMAIL),
              params,
              TemplateKeys.SENSITIVE_DIGEST + ":" + day));
    }
    return !recipients.isEmpty();
  }

  /** The sensitive actions among the entries, oldest first. */
  static List<Item> items(List<AuditEntryView> entries) {
    List<Item> items = new ArrayList<>();
    for (AuditEntryView e : entries) {
      Item item = item(e);
      if (item != null) {
        items.add(item);
      }
    }
    items.sort(java.util.Comparator.comparing(Item::at));
    return items;
  }

  /** What one entry is, in the digest's words; null when it is not a sensitive action. */
  static Item item(AuditEntryView e) {
    Map<String, Object> after = e.after() == null ? Map.of() : e.after();
    String label = e.label() == null ? "" : " " + e.label();
    String actor = e.actor();
    String action = e.action();
    String type = e.entityType() == null ? "" : e.entityType();
    return switch (action) {
      case "APPROVAL_GRANTED" -> {
        String approved = String.valueOf(after.getOrDefault("action", "?"));
        Kind kind =
            approved.equals("DEEP_DISCOUNT") || approved.equals("DEEP_PROMOTION")
                ? Kind.DISCOUNTS
                : Kind.APPROVALS;
        yield new Item(
            kind,
            e.at(),
            String.valueOf(after.getOrDefault("approvedBy", actor)),
            "Approved "
                + approved.toLowerCase(Locale.ROOT).replace('_', ' ')
                + amount(after.get("amount"))
                + text(after.get("reference")));
      }
      case "STOCK_ADJUSTED" ->
          new Item(
              Kind.STOCK,
              e.at(),
              actor,
              "Stock adjusted: "
                  + after.getOrDefault("product", "?")
                  + " "
                  + after.getOrDefault("quantityChange", "")
                  + " ("
                  + String.valueOf(after.getOrDefault("reason", "")).toLowerCase(Locale.ROOT)
                  + ")"
                  + amount(after.get("value")));
      case "PERIOD_LOCK_CHANGED" ->
          new Item(
              Kind.PERIOD,
              e.at(),
              actor,
              "Books locked through "
                  + (e.before() == null ? "—" : e.before().getOrDefault("lockedThrough", "—"))
                  + " → "
                  + after.getOrDefault("lockedThrough", "—"));
      case "SCHEDULE_CHANGED" ->
          new Item(
              Kind.SCHEDULE,
              e.at(),
              actor,
              after.getOrDefault("product", "A medicine")
                  + " class "
                  + (e.before() == null ? "?" : e.before().getOrDefault("schedule", "?"))
                  + " → "
                  + after.getOrDefault("schedule", "?"));
      case "ROLE_PERMISSIONS_CHANGED", "MEMBER_LEFT" ->
          new Item(Kind.ROLES, e.at(), actor, readable(action) + label);
      case "CREATE", "UPDATE", "DELETE" -> change(e, type, label, after);
      default -> null;
    };
  }

  private static Item change(
      AuditEntryView e, String type, String label, Map<String, Object> after) {
    String verb = e.action().toLowerCase(Locale.ROOT) + "d";
    return switch (type) {
      case "SaleReturn" ->
          "CREATE".equals(e.action())
              ? new Item(
                  Kind.RETURNS,
                  e.at(),
                  e.actor(),
                  "Return" + label + amount(after.get("refundTotal")))
              : null;
      case "Destruction" ->
          "CREATE".equals(e.action())
              ? new Item(Kind.STOCK, e.at(), e.actor(), "Destruction" + label)
              : null;
      case "Voucher" ->
          "CREATE".equals(e.action())
              ? new Item(
                  Kind.VOUCHERS, e.at(), e.actor(), "Voucher" + label + amount(after.get("value")))
              : null;
      case "Role" -> new Item(Kind.ROLES, e.at(), e.actor(), "Role" + label + " " + verb);
      case "Membership" ->
          new Item(Kind.ROLES, e.at(), e.actor(), "Staff access" + label + " " + verb);
      case "Entry" ->
          "CREATE".equals(e.action()) && "WRITE_OFF".equals(String.valueOf(after.get("type")))
              ? new Item(
                  Kind.WRITE_OFFS,
                  e.at(),
                  e.actor(),
                  "Debt written off" + label + amount(after.get("amount")))
              : null;
      default -> null;
    };
  }

  private static String amount(Object value) {
    return value == null ? "" : " " + value;
  }

  private static String text(Object value) {
    return value == null || String.valueOf(value).isBlank() ? "" : " — " + value;
  }

  private static String readable(String action) {
    String words = action.toLowerCase(Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  private List<TenantView> activeTenants() {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(JOB, null, null),
        () -> {
          List<TenantView> all = new ArrayList<>();
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

  private static <T> T inTenant(UUID tenantUid, java.util.function.Supplier<T> work) {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(JOB, null, tenantUid),
        () -> TenantContext.callAs(tenantUid, work::get));
  }
}
