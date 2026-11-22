package tz.co.pharmacy.modules.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * the after-commit sender and the retry job safe to run at the same time. A channel's providers are
 * tried in order until one accepts the message (failover); when all fail, the message is retried
 * later with backoff, from the first provider again.
 */
@Component
class Dispatcher {

  private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
  private static final String SERVICE = "notifications";

  private final MessageRepository messages;
  private final InboxRepository inbox;
  private final ProviderRouter router;
  private final tz.co.pharmacy.modules.notifications.providers.ProviderHealth health;
  private final tz.co.pharmacy.modules.notifications.persistence.ProviderSettingRepository
      providers;
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
      tz.co.pharmacy.modules.notifications.providers.ProviderHealth health,
      tz.co.pharmacy.modules.notifications.persistence.ProviderSettingRepository providers,
      PendingScopes pending,
      ApplicationEventPublisher events,
      @Qualifier("notificationSenders") ExecutorService senders,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.clock = clock;
    this.messages = messages;
    this.inbox = inbox;
    this.router = router;
    this.health = health;
    this.providers = providers;
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
    if (message.getChannel() != Channel.IN_APP) {
      // Failover: the providers in order (the pharmacy's own, else the platform's, else the
      // environment's); the first that accepts the message sends it.
      List<String> failures = new ArrayList<>();
      for (ProviderRouter.Route route : router.routes(message.getChannel())) {
        provider = route.label();
        try {
          route
              .sender()
              .send(
                  message.getToAddress(),
                  message.getSubject(),
                  message.getHtmlBody(),
                  message.getBody());
          noteSent(route);
          error = null;
          break;
        } catch (RuntimeException failure) {
          // The delivery log gets plain words; the raw failure stays in the server log.
          String readable =
              tz.co.pharmacy.modules.notifications.providers.ProviderErrors.readable(
                  failure, route.provider());
          failures.add(route.label() + ": " + readable);
          error = String.join(" | ", failures);
          noteFailed(route, readable);
          log.warn(
              "Notification {} via {} failed (attempt {}): {}",
              message.getUid(),
              route.label(),
              message.getAttempts() + 1,
              failure.toString());
        }
      }
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

  /** A provider accepted a message: its circuit closes and its row notes the success. */
  private void noteSent(ProviderRouter.Route route) {
    if (route.uid() == null) {
      return;
    }
    health.succeeded(route.uid());
    Instant now = clock.instant();
    inScopeOf(
        route,
        () ->
            transactions.executeWithoutResult(
                status -> providers.markSent(route.uid(), now, now.minusSeconds(60))));
  }

  /** A provider refused or failed: counted toward its circuit breaker and noted on its row. */
  private void noteFailed(ProviderRouter.Route route, String error) {
    if (route.uid() == null) {
      return;
    }
    health.failed(route.uid());
    String trimmed = error != null && error.length() > 500 ? error.substring(0, 500) : error;
    inScopeOf(
        route,
        () ->
            transactions.executeWithoutResult(
                status -> providers.markFailed(route.uid(), clock.instant(), trimmed)));
  }

  /** The platform's providers are written in the platform's scope, a pharmacy's in its own. */
  private static void inScopeOf(ProviderRouter.Route route, Runnable work) {
    try {
      if (ProviderRouter.SOURCE_PLATFORM.equals(route.source())) {
        TenantContext.callAs(
            null,
            () -> {
              work.run();
              return null;
            });
      } else {
        work.run();
      }
    } catch (RuntimeException ignored) {
      // A status note must never stop a delivery.
      log.debug("could not note the provider status of {}", route.label(), ignored);
    }
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
