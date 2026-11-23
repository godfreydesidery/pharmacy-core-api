package tz.co.pharmacy.modules.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tz.co.pharmacy.modules.notifications.domain.InboxItem;
import tz.co.pharmacy.modules.notifications.domain.Message;
import tz.co.pharmacy.modules.notifications.domain.Preference;
import tz.co.pharmacy.modules.notifications.persistence.InboxRepository;
import tz.co.pharmacy.modules.notifications.persistence.MessageRepository;
import tz.co.pharmacy.modules.notifications.persistence.PreferenceRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.modules.notifications.ports.outbound.NotificationDirectory;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Queues messages in the caller's transaction (the outbox) and sends them after it commits. An
 * in-app message is written to the inbox at once. Every message is also due for the retry job two
 * minutes later, so a send lost to a crash is still made.
 */
@Service
class NotifierImpl implements Notifier {

  private static final Duration SAFETY_NET = Duration.ofMinutes(2);

  private final MessageRepository messages;
  private final InboxRepository inbox;
  private final PreferenceRepository preferences;
  private final TemplateResolver templates;
  private final NotificationDirectory directory;
  private final PendingScopes pending;
  private final Dispatcher dispatcher;
  private final NotificationMetrics metrics;
  private final String defaultCountryCode;

  @SuppressWarnings("java:S107")
  private final Clock clock;

  NotifierImpl(
      MessageRepository messages,
      InboxRepository inbox,
      PreferenceRepository preferences,
      TemplateResolver templates,
      NotificationDirectory directory,
      PendingScopes pending,
      Dispatcher dispatcher,
      NotificationMetrics metrics,
      @Value("${pharmacy.notifications.default-country-code:255}") String defaultCountryCode,
      Clock clock) {
    this.metrics = metrics;
    this.clock = clock;
    this.messages = messages;
    this.inbox = inbox;
    this.preferences = preferences;
    this.templates = templates;
    this.directory = directory;
    this.pending = pending;
    this.dispatcher = dispatcher;
    this.defaultCountryCode = defaultCountryCode;
  }

  @Override
  @Transactional
  public void send(NotificationRequest request) {
    if (request == null
        || request.templateKey() == null
        || !TemplateCatalog.known(request.templateKey())) {
      throw Refusal.invalid("unknown_template", "No such notification template.", "templateKey");
    }
    if (request.recipient() == null || request.channels() == null || request.channels().isEmpty()) {
      return;
    }
    UUID tenantUid = TenantContext.tenantUid().orElse(null);
    Recipient recipient = complete(request.recipient());
    String key = request.templateKey();
    String category = TemplateKeys.category(key);
    String locale = TemplateCatalog.locale(recipient.locale());
    Map<String, Object> params =
        new HashMap<>(request.params() == null ? Map.of() : request.params());
    params.putIfAbsent("name", recipient.name() == null ? "" : recipient.name());
    Instant now = clock.instant();

    List<UUID> queued = new ArrayList<>();
    List<Channel> counted = new ArrayList<>();
    Set<UUID> inboxUsers = new LinkedHashSet<>();
    for (Channel channel : channels(request.channels(), recipient, category, tenantUid)) {
      String address = address(channel, recipient);
      if (address == null) {
        continue;
      }
      if (request.dedupKey() != null
          && messages.existsByDedupKeyAndChannelAndToAddressAndCreatedAtAfter(
              request.dedupKey(), channel, address, now.minus(Duration.ofHours(24)))) {
        continue;
      }
      TemplateResolver.Rendered rendered = templates.render(key, channel, locale, params);
      Message message =
          new Message(
              key,
              category,
              channel,
              recipient.userUid(),
              recipient.name(),
              address,
              locale,
              rendered.subject(),
              rendered.text(),
              rendered.html(),
              request.dedupKey(),
              now.plus(SAFETY_NET));
      if (channel == Channel.IN_APP) {
        inbox.save(
            new InboxItem(
                recipient.userUid(),
                rendered.subject() == null || rendered.subject().isBlank()
                    ? key
                    : rendered.subject(),
                rendered.text(),
                params.get("link") == null ? null : String.valueOf(params.get("link")),
                severity(params.get("severity"))));
        message.deliveredInApp();
        messages.save(message);
        inboxUsers.add(recipient.userUid());
      } else {
        messages.save(message);
        queued.add(message.getUid());
      }
      counted.add(channel);
    }
    if (queued.isEmpty() && inboxUsers.isEmpty()) {
      return;
    }
    if (!queued.isEmpty()) {
      pending.due(PendingScopes.scopeOf(tenantUid), now.plus(SAFETY_NET));
    }
    afterCommit(
        () -> {
          counted.forEach(metrics::queued);
          dispatcher.dispatchAsync(tenantUid, queued, inboxUsers);
        });
  }

  private Recipient complete(Recipient given) {
    if (given.userUid() == null || (given.email() != null && given.name() != null)) {
      return given;
    }
    return directory
        .user(given.userUid())
        .map(
            known ->
                new Recipient(
                    given.userUid(),
                    given.name() != null ? given.name() : known.name(),
                    given.email() != null ? given.email() : known.email(),
                    given.phone() != null ? given.phone() : known.phone(),
                    given.locale() != null ? given.locale() : known.locale()))
        .orElse(given);
  }

  /** The requested channels the user has not turned off; access messages ignore preferences. */
  private Set<Channel> channels(
      Set<Channel> requested, Recipient recipient, String category, UUID tenantUid) {
    Set<Channel> chosen = EnumSet.copyOf(requested);
    if (recipient.userUid() != null && tenantUid != null && !"access".equals(category)) {
      preferences
          .findByUserUidAndCategory(recipient.userUid(), category)
          .map(Preference::channelSet)
          .ifPresent(chosen::retainAll);
    }
    return chosen;
  }

  private String address(Channel channel, Recipient recipient) {
    return switch (channel) {
      case EMAIL -> blankToNull(recipient.email());
      case SMS, WHATSAPP -> Phones.e164(recipient.phone(), defaultCountryCode);
      case IN_APP -> recipient.userUid() == null ? null : recipient.userUid().toString();
    };
  }

  private static String severity(Object value) {
    String severity = value == null ? "info" : String.valueOf(value);
    return switch (severity) {
      case "warning", "critical" -> severity;
      default -> "info";
    };
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static void afterCommit(Runnable work) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              work.run();
            }
          });
    } else {
      work.run();
    }
  }
}
