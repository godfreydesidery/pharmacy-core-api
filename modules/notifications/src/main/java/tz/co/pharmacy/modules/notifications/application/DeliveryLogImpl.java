package tz.co.pharmacy.modules.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tz.co.pharmacy.modules.notifications.domain.Message;
import tz.co.pharmacy.modules.notifications.domain.MessageStatus;
import tz.co.pharmacy.modules.notifications.persistence.MessageRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.DeliveryDetail;
import tz.co.pharmacy.modules.notifications.ports.inbound.DeliveryLog;
import tz.co.pharmacy.modules.notifications.ports.inbound.DeliveryView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;
import tz.co.pharmacy.platform.persistence.TenantContext;

@Service
class DeliveryLogImpl implements DeliveryLog {

  /** Query values of these names in links are secrets (reset tokens, invitation tokens). */
  private static final Pattern SECRET =
      Pattern.compile(
          "([?&](?:token|code|key|secret|signature|sig)=)[^&\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

  private static final Duration SAFETY_NET = Duration.ofMinutes(2);

  private final MessageRepository messages;
  private final Dispatcher dispatcher;
  private final PendingScopes pending;
  private final Clock clock;

  DeliveryLogImpl(
      MessageRepository messages, Dispatcher dispatcher, PendingScopes pending, Clock clock) {
    this.messages = messages;
    this.dispatcher = dispatcher;
    this.pending = pending;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public DeliveryDetail detail(UUID messageUid) {
    Message m = required(messageUid);
    return new DeliveryDetail(
        m.getUid(),
        m.getTemplateKey(),
        m.getChannel(),
        m.getRecipientName(),
        m.getToAddress(),
        m.getLocale(),
        mask(m.getSubject()),
        mask(m.getBody()),
        m.getStatus().name(),
        m.getProvider(),
        m.getAttempts(),
        m.getLastError(),
        m.getCreatedAt(),
        m.getSentAt());
  }

  @Override
  @Transactional
  public DeliveryView resend(UUID messageUid) {
    Message original = required(messageUid);
    if (original.getChannel() == Channel.IN_APP) {
      throw Refusal.invalid(
          "cannot_resend", "In-app messages stay in the inbox; nothing to resend.", "messageUid");
    }
    Instant now = clock.instant();
    Message copy =
        messages.save(
            new Message(
                original.getTemplateKey(),
                original.getCategory(),
                original.getChannel(),
                original.getRecipientUserUid(),
                original.getRecipientName(),
                original.getToAddress(),
                original.getLocale(),
                original.getSubject(),
                original.getBody(),
                original.getHtmlBody(),
                null,
                now));
    UUID tenantUid = TenantContext.tenantUid().orElse(null);
    pending.due(PendingScopes.scopeOf(tenantUid), now.plus(SAFETY_NET));
    UUID uid = copy.getUid();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatcher.dispatchAsync(tenantUid, List.of(uid), List.of());
            }
          });
    }
    return view(copy);
  }

  /** Masks secret query values in links: …reset-password?token=••• */
  static String mask(String text) {
    return text == null ? null : SECRET.matcher(text).replaceAll("$1•••");
  }

  private Message required(UUID messageUid) {
    return messages
        .findByUid(Checks.required(messageUid, "messageUid"))
        .orElseThrow(() -> Refusal.notFound("delivery_not_found", "No such message."));
  }

  private static DeliveryView view(Message m) {
    return new DeliveryView(
        m.getUid(),
        m.getTemplateKey(),
        m.getChannel(),
        m.getToAddress(),
        m.getStatus().name(),
        m.getProvider(),
        m.getAttempts(),
        m.getLastError(),
        m.getCreatedAt(),
        m.getSentAt());
  }

  @Override
  @Transactional(readOnly = true)
  public Page<DeliveryView> list(Channel channel, String status, PageQuery query) {
    MessageStatus wanted =
        status == null || status.isBlank()
            ? null
            : Checks.oneOf(MessageStatus.class, status, "status");
    Specification<Message> filter =
        Specs.<Message>equal("channel", channel).and(Specs.equal("status", wanted));
    return Keyset.page(messages, filter, query, DeliveryLogImpl::view);
  }
}
