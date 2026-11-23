package tz.co.pharmacy.modules.notifications.application;

import java.time.Clock;
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
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;

/**
 * Every minute, sends again whatever is due: failed messages whose backoff has passed, queued ones
 * whose after-commit send never ran (such as after a restart), and claimed ones whose sender died.
 * It visits only the scopes {@link PendingScopes} lists, each in its own tenant.
 *
 * <p>With several instances, one runs a round at a time ({@link SingleRunner}, a PostgreSQL
 * advisory lock), and each message is still claimed by one sender only ({@code
 * MessageRepository.claim}, a conditional update that one caller wins: PostgreSQL re-checks its
 * condition after waiting for a competing claim), so the after-commit senders on every instance and
 * this job never send a message twice. A scope that fails is recorded with the run.
 */
@Component
class RetryJob {

  static final String JOB = "notification-retries";

  private final PendingScopes pending;
  private final Dispatcher dispatcher;
  private final MessageRepository messages;
  private final TransactionTemplate transactions;
  private final SingleRunner runner;

  private final Clock clock;

  RetryJob(
      PendingScopes pending,
      Dispatcher dispatcher,
      MessageRepository messages,
      PlatformTransactionManager transactionManager,
      SingleRunner runner,
      Clock clock) {
    this.runner = runner;
    this.clock = clock;
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
    runner.runAlone(JOB, this::retryRound);
  }

  void retryRound(JobRun run) {
    Instant now = clock.instant();
    int scopes = 0;
    int tried = 0;
    for (UUID scope : pending.dueBy(now)) {
      UUID tenant = PendingScopes.tenantOf(scope);
      int[] count = {0};
      try {
        Dispatcher.inScope(
            tenant,
            null,
            () -> {
              List<UUID> due =
                  transactions.execute(
                      status -> messages.due(now, Message.MAX_ATTEMPTS, PageRequest.of(0, 200)));
              if (due != null) {
                due.forEach(dispatcher::deliver);
                count[0] = due.size();
              }
              dispatcher.settle(tenant);
            });
      } catch (RuntimeException failure) {
        run.failed(tenant, failure);
      }
      scopes++;
      tried += count[0];
    }
    if (tried > 0) {
      run.note(tried + " messages tried in " + scopes + " scopes");
    }
  }
}
