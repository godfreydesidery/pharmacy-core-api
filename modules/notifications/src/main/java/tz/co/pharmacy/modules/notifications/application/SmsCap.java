package tz.co.pharmacy.modules.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.notifications.domain.InboxItem;
import tz.co.pharmacy.modules.notifications.domain.Message;
import tz.co.pharmacy.modules.notifications.persistence.InboxRepository;
import tz.co.pharmacy.modules.notifications.persistence.MessageRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.modules.notifications.ports.outbound.NotificationDirectory;
import tz.co.pharmacy.modules.notifications.ports.outbound.SmsAllowance;

/**
 * The plan's SMS a month on the platform's account (adversarial review INT-5). Before an SMS goes
 * through the platform's providers (or the environment's), the pharmacy's SMS sent on the
 * platform's account this calendar month, in the pharmacy's time zone, are counted from the
 * delivery log, with the ones other senders are sending at that moment. At the cap the message is
 * not sent: it is marked FAILED with reason {@code sms_cap_reached}, and the first time in the
 * month the pharmacy's administrators get an inbox notice. A pharmacy's own SMS provider is not
 * capped.
 *
 * <p>The count runs under a transaction-scoped PostgreSQL advisory lock per pharmacy, so senders on
 * several instances check one after another; nothing is counted in memory.
 */
@Component
class SmsCap {

  static final String REASON = "sms_cap_reached";

  /** Who is told: the pharmacy's administrators (those who manage users). */
  static final String ADMIN_PERMISSION = "users.manage";

  private final MessageRepository messages;
  private final InboxRepository inbox;
  private final SmsAllowance allowance;
  private final NotificationDirectory directory;
  private final TemplateResolver templates;
  private final TransactionTemplate transactions;
  private final Clock clock;

  SmsCap(
      MessageRepository messages,
      InboxRepository inbox,
      SmsAllowance allowance,
      NotificationDirectory directory,
      TemplateResolver templates,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.messages = messages;
    this.inbox = inbox;
    this.allowance = allowance;
    this.directory = directory;
    this.templates = templates;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** A refused SMS: the words for the delivery log, and the administrators given a notice. */
  record Refused(String error, List<UUID> noticed) {}

  /** The calendar month of {@code now} in {@code zone}, as [start, end). */
  record Window(YearMonth month, Instant from, Instant to) {

    static Window of(Instant now, ZoneId zone) {
      YearMonth month = YearMonth.from(now.atZone(zone));
      return new Window(
          month,
          month.atDay(1).atStartOfDay(zone).toInstant(),
          month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    }
  }

  /**
   * Admits a claimed SMS of the pharmacy to be sent on the platform's account, or refuses it (the
   * message is then marked FAILED {@code sms_cap_reached}, not to be retried).
   */
  Optional<Refused> admit(UUID tenantUid, UUID messageUid, String provider) {
    SmsAllowance.Allowance plan = allowance.of(tenantUid);
    if (plan == null || plan.perMonth() == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    Window window = Window.of(now, zone(plan));
    return Optional.ofNullable(
        transactions.execute(
            status -> {
              messages.lock("notifications.sms-cap:" + tenantUid);
              long used =
                  messages.countPlatformSmsSent(tenantUid, window.from(), window.to())
                      + messages.countSmsInFlight(tenantUid, now, messageUid);
              if (used < plan.perMonth()) {
                return null;
              }
              String error = words(plan);
              messages
                  .findByUid(messageUid)
                  .ifPresent(message -> message.refused(provider, REASON, error));
              return new Refused(error, notice(plan, window));
            }));
  }

  /** For a test send through the platform's account: why it is refused, or empty. */
  Optional<String> refusal(UUID tenantUid) {
    SmsAllowance.Allowance plan = allowance.of(tenantUid);
    if (plan == null || plan.perMonth() == null) {
      return Optional.empty();
    }
    Window window = Window.of(clock.instant(), zone(plan));
    Long used =
        transactions.execute(
            status -> messages.countPlatformSmsSent(tenantUid, window.from(), window.to()));
    return used != null && used >= plan.perMonth() ? Optional.of(words(plan)) : Optional.empty();
  }

  /** The month's inbox notice to the administrators, once a month (inside the locked count). */
  private List<UUID> notice(SmsAllowance.Allowance plan, Window window) {
    String dedup = "sms-cap:" + window.month();
    if (messages.existsByTemplateKeyAndDedupKey(TemplateKeys.SMS_CAP_REACHED, dedup)) {
      return List.of();
    }
    Map<String, Object> params =
        Map.of(
            "cap",
            plan.perMonth(),
            "planName",
            plan.planName() == null ? "" : plan.planName(),
            "month",
            window.month().toString(),
            "link",
            "/settings/notifications",
            "severity",
            "warning");
    List<UUID> noticed = new ArrayList<>();
    for (Recipient admin : directory.usersWithPermission(ADMIN_PERMISSION, null)) {
      if (admin.userUid() == null) {
        continue;
      }
      String locale = TemplateCatalog.locale(admin.locale());
      TemplateResolver.Rendered rendered =
          templates.render(TemplateKeys.SMS_CAP_REACHED, Channel.IN_APP, locale, params);
      inbox.save(
          new InboxItem(
              admin.userUid(),
              rendered.subject(),
              rendered.text(),
              "/settings/notifications",
              "warning"));
      Message message =
          new Message(
              TemplateKeys.SMS_CAP_REACHED,
              TemplateKeys.category(TemplateKeys.SMS_CAP_REACHED),
              Channel.IN_APP,
              admin.userUid(),
              admin.name(),
              admin.userUid().toString(),
              locale,
              rendered.subject(),
              rendered.text(),
              null,
              dedup,
              null);
      message.deliveredInApp();
      messages.save(message);
      noticed.add(admin.userUid());
    }
    return noticed;
  }

  private static String words(SmsAllowance.Allowance plan) {
    return REASON
        + ": the "
        + (plan.planName() == null ? "" : plan.planName() + " ")
        + "plan includes "
        + plan.perMonth()
        + " SMS a month on the platform's account, and this month's are used. Add the"
        + " pharmacy's own SMS provider, or upgrade the plan.";
  }

  private static ZoneId zone(SmsAllowance.Allowance plan) {
    return plan.zone() == null ? ZoneId.of("UTC") : plan.zone();
  }
}
