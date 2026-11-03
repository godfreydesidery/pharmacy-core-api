package tz.co.pharmacy.modules.notifications.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.notifications.domain.Message;
import tz.co.pharmacy.modules.notifications.persistence.MessageRepository;

/**
 * Every minute, sends again whatever is due: failed messages whose backoff has passed, queued ones
 * whose after-commit send never ran (such as after a restart), and claimed ones whose sender died.
 * It visits only the scopes {@link PendingScopes} lists, each in its own tenant.
 */
@Component
class RetryJob {

  private final PendingScopes pending;
  private final Dispatcher dispatcher;
  private final MessageRepository messages;
  private final TransactionTemplate transactions;

  RetryJob(
      PendingScopes pending,
      Dispatcher dispatcher,
      MessageRepository messages,
      PlatformTransactionManager transactionManager) {
    this.pending = pending;
    this.dispatcher = dispatcher;
    this.messages = messages;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setReadOnly(true);
  }

  @Scheduled(
      initialDelayString = "${pharmacy.notifications.retry.initial-delay:PT30S}",
      fixedDelayString = "${pharmacy.notifications.retry.every:PT1M}")
  void retryDue() {
    Instant now = Instant.now();
    for (UUID scope : pending.dueBy(now)) {
      UUID tenant = PendingScopes.tenantOf(scope);
      Dispatcher.inScope(
          tenant,
          null,
          () -> {
            List<UUID> due =
                transactions.execute(
                    status -> messages.due(now, Message.MAX_ATTEMPTS, PageRequest.of(0, 200)));
            if (due != null) {
              due.forEach(dispatcher::deliver);
            }
            dispatcher.settle(tenant);
          });
    }
  }
}
