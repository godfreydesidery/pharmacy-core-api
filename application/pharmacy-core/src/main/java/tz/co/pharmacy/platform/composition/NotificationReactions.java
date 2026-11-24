package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.inventory.events.StockLow;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.modules.notifications.ports.outbound.NotificationDirectory;
import tz.co.pharmacy.modules.partners.ports.inbound.ConsentPurpose;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerConsents;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.sales.events.SaleCompleted;
import tz.co.pharmacy.modules.tenancy.events.TenantRegistered;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.bus.EventScopes;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * Turns events into notifications: a low stock warning to the people who restock that branch, an
 * SMS receipt to a customer when the pharmacy sends them, and a welcome to a new pharmacy's
 * administrator. Each runs after commit, as the notifications service, in the event's tenant.
 */
@Component
class NotificationReactions {

  private static final String SERVICE = "notification-reactions";
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  private final Notifier notifier;
  private final NotificationDirectory directory;
  private final SettingsService settings;
  private final BranchService branches;
  private final TenantService tenants;
  private final CustomerService customers;
  private final CustomerConsents consents;
  private final String webUrl;

  NotificationReactions(
      Notifier notifier,
      NotificationDirectory directory,
      SettingsService settings,
      BranchService branches,
      TenantService tenants,
      CustomerService customers,
      CustomerConsents consents,
      @Value("${pharmacy.access.web-url:http://localhost:4300}") String webUrl) {
    this.consents = consents;
    this.notifier = notifier;
    this.directory = directory;
    this.settings = settings;
    this.branches = branches;
    this.tenants = tenants;
    this.customers = customers;
    this.webUrl = webUrl;
  }

  @Async
  @TransactionalEventListener
  public void on(StockLow event) {
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () -> {
          if (!settings.current().lowStockAlerts()) {
            return;
          }
          UUID branchUid = event.metadata().branchUid();
          String branchName =
              branchUid == null
                  ? "your pharmacy"
                  : branches.find(branchUid).map(BranchView::name).orElse("your branch");
          String today = LocalDate.now(Clocks.clock()).toString();
          for (Recipient recipient : directory.usersWithPermission("inventory.adjust", branchUid)) {
            notifier.send(
                new NotificationRequest(
                    TemplateKeys.STOCK_LOW,
                    recipient,
                    EnumSet.of(Channel.IN_APP, Channel.EMAIL),
                    Map.of(
                        "productName",
                        event.productName(),
                        "quantity",
                        event.quantity(),
                        "reorderLevel",
                        event.reorderLevel(),
                        "branchName",
                        branchName,
                        "severity",
                        event.quantity() == 0 ? "critical" : "warning",
                        "link",
                        "/inventory/stock"),
                    "stock.low:" + event.productUid() + ":" + branchUid + ":" + today));
          }
        });
  }

  @Async
  @TransactionalEventListener
  public void on(SaleCompleted event) {
    if (event.customerUid() == null) {
      return;
    }
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () -> {
          SettingsView current = settings.current();
          if (!current.smsReceipts()) {
            return;
          }
          // STD-5: a customer who withdrew (or refused) SMS receipts gets none.
          if (!consents.allows(event.customerUid(), ConsentPurpose.SMS_RECEIPTS)) {
            return;
          }
          customers
              .find(event.customerUid())
              .filter(customer -> customer.phone() != null && !customer.phone().isBlank())
              .ifPresent(
                  customer -> {
                    String pharmacy =
                        tenants
                            .find(event.metadata().tenantUid())
                            .map(TenantView::name)
                            .orElse("Your pharmacy");
                    ZoneId zone = ZoneId.of(current.timezone());
                    notifier.send(
                        new NotificationRequest(
                            TemplateKeys.SALE_RECEIPT,
                            new Recipient(null, customer.name(), null, customer.phone(), null),
                            Set.of(Channel.SMS),
                            Map.of(
                                "pharmacyName", pharmacy,
                                "number", event.number(),
                                "total", money(current.currency(), event.total()),
                                "date", DAY.format(event.metadata().occurredAt().atZone(zone))),
                            "sale.receipt:" + event.saleUid()));
                  });
        });
  }

  /** The new tenant's administrators are welcomed in the new tenant's own scope. */
  @Async
  @TransactionalEventListener
  public void on(TenantRegistered event) {
    EventMetadata metadata = event.metadata();
    EventMetadata inTenant =
        new EventMetadata(
            metadata.eventId(),
            metadata.type(),
            event.tenantUid(),
            null,
            metadata.occurredAt(),
            metadata.actorReference(),
            metadata.correlationId());
    EventScopes.run(
        inTenant,
        SERVICE,
        () -> {
          for (Recipient admin : directory.usersWithPermission("users.manage", null)) {
            notifier.send(
                new NotificationRequest(
                    TemplateKeys.TENANT_WELCOME,
                    admin,
                    EnumSet.of(Channel.EMAIL, Channel.IN_APP),
                    Map.of("pharmacyName", event.name(), "link", webUrl),
                    "tenant.welcome:" + event.tenantUid()));
          }
        });
  }

  static String money(String currency, BigDecimal amount) {
    return currency + " " + String.format(Locale.ENGLISH, "%,.2f", amount);
  }
}
