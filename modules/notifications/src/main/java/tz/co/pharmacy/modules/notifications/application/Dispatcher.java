package tz.co.pharmacy.modules.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.notifications.domain.InboxItem;
import tz.co.pharmacy.modules.notifications.domain.Message;
import tz.co.pharmacy.modules.notifications.events.InAppDelivered;
import tz.co.pharmacy.modules.notifications.persistence.InboxRepository;
import tz.co.pharmacy.modules.notifications.persistence.MessageRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppView;
import tz.co.pharmacy.modules.notifications.providers.ProviderRouter;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Sends queued messages. A message is claimed in one short transaction, sent with no transaction
 * open (a slow provider holds no connection), and its outcome recorded in another. The claim makes
 * the after-commit sender and the retry job safe to run at the same time.
 */
@Component
class Dispatcher {

  private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
  private static final String SERVICE = "notifications";

  private final MessageRepository messages;
  private final InboxRepository inbox;
  private final ProviderRouter router;
  private final PendingScopes pending;
  private final ApplicationEventPublisher events;
  private final ExecutorService senders;
  private final TransactionTemplate transactions;

  @SuppressWarnings("java:S107")
  private final Clock clock;

  Dispatcher(
      MessageRepository messages,
      InboxRepository inbox,
      ProviderRouter router,
      PendingScopes pending,
      ApplicationEventPublisher events,
      @Qualifier("notificationSenders") ExecutorService senders,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.clock = clock;
    this.messages = messages;
    this.inbox = inbox;
    this.router = router;
    this.pending = pending;
    this.events = events;
    this.senders = senders;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Sends the messages and announces the inbox items, on a virtual thread, in the given scope. */
  void dispatchAsync(UUID tenantUid, List<UUID> messageUids, Collection<UUID> inboxUsers) {
    String correlationId = ExecutionContext.correlationId().orElse(null);
    senders.submit(
        () ->
            inScope(
                tenantUid,
                correlationId,
                () -> {
                  messageUids.forEach(this::deliver);
                  inboxUsers.forEach(user -> announce(tenantUid, user));
                  settle(tenantUid);
                }));
  }

  /** Runs work as the notifications service in the tenant (or platform) scope. */
  static void inScope(UUID tenantUid, String correlationId, Runnable work) {
    ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, correlationId, tenantUid),
        () ->
            TenantContext.callAs(
                tenantUid,
                () -> {
                  work.run();
                  return null;
                }));
  }

  /** Sends one message if this caller wins its claim; the current thread is in its scope. */
  void deliver(UUID messageUid) {
    Instant now = clock.instant();
    Integer claimed =
        transactions.execute(
            status -> messages.claim(messageUid, now, now.plusSeconds(300), Message.MAX_ATTEMPTS));
    if (claimed == null || claimed == 0) {
      return;
    }
    Optional<Message> found =
        Optional.ofNullable(
            transactions.execute(status -> messages.findByUid(messageUid).orElse(null)));
    if (found.isEmpty()) {
      return;
    }
    Message message = found.get();
    String provider = message.getChannel() == Channel.IN_APP ? "in-app" : null;
    String error = null;
    try {
      if (message.getChannel() != Channel.IN_APP) {
        // The pharmacy's own account, else the platform's, else the environment's provider.
        ProviderRouter.Route route = router.route(message.getChannel());
        provider = route.label();
        route
            .sender()
            .send(
                message.getToAddress(),
                message.getSubject(),
                message.getHtmlBody(),
                message.getBody());
      }
    } catch (RuntimeException failure) {
      // The delivery log gets plain words; the raw failure stays in the server log.
      error =
          tz.co.pharmacy.modules.notifications.providers.ProviderErrors.readable(failure, provider);
      log.warn(
          "Notification {} via {} failed (attempt {}): {}",
          message.getUid(),
          provider,
          message.getAttempts() + 1,
          failure.toString());
    }
    String outcome = error;
    String used = provider == null ? "unresolved" : provider;
    transactions.executeWithoutResult(
        status ->
            messages
                .findByUid(messageUid)
                .ifPresent(
                    row -> {
                      if (outcome == null) {
                        row.sent(used);
                      } else {
                        row.failed(
                            used,
                            outcome,
                            clock.instant().plus(Backoff.after(row.getAttempts() + 1)));
                      }
                    }));
  }

  /** Recomputes when the scope next has a message due, for the retry job. */
  void settle(UUID tenantUid) {
    Instant earliest = transactions.execute(status -> messages.earliestDue(Message.MAX_ATTEMPTS));
    pending.settle(PendingScopes.scopeOf(tenantUid), earliest);
  }

  private void announce(UUID tenantUid, UUID userUid) {
    record Snapshot(InAppView latest, long unread) {}
    Snapshot snapshot =
        transactions.execute(
            status ->
                new Snapshot(
                    inbox
                        .findFirstByUserUidOrderByUidDesc(userUid)
                        .map(Dispatcher::view)
                        .orElse(null),
                    inbox.countByUserUidAndReadAtIsNull(userUid)));
    if (snapshot != null && snapshot.latest() != null) {
      events.publishEvent(
          new InAppDelivered(tenantUid, userUid, snapshot.latest(), snapshot.unread()));
    }
  }

  static InAppView view(InboxItem item) {
    return new InAppView(
        item.getUid(),
        item.getTitle(),
        item.getBody(),
        item.getLink(),
        item.getSeverity(),
        item.getReadAt() != null,
        item.getCreatedAt());
  }
}
