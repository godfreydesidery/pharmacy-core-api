package tz.co.pharmacy.platform.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The one executor of every {@code @Async} method: the event reactions that run after commit (the
 * ledger postings, read models, webhooks and notices of a sale, four or more per sale). Before it,
 * each ran on a thread of its own with nothing to bound them, so a busy minute could open as many
 * database transactions as there were events (adversarial review PERF-2).
 *
 * <p>A fixed pool of {@code pharmacy.async.threads} threads (8: under half the database pool of 20,
 * so the reactions never take every connection from the requests) takes the work in order from a
 * queue of at most {@code pharmacy.async.queue} tasks (10,000). The policy when the queue is full
 * is to <b>refuse</b> the task (logged {@code ASYNC_REJECTED}, counted): the caller is never made
 * to wait or to run the reaction itself, as it may still hold its own connection. Nothing is lost,
 * since every {@code @Async} reaction is a {@code @TransactionalEventListener} whose event stays an
 * incomplete publication in the event registry until it ran; the {@code event-resubmission} job
 * delivers it again (every 5 minutes, one instance).
 *
 * <p>On shutdown the pool stops taking work and finishes what it is running, for at most {@code
 * pharmacy.async.shutdown-wait} seconds (20); what was still queued is delivered again the same
 * way.
 */
@Configuration(proxyBeanMethods = false)
public class AsyncExecution {

  public static final String EXECUTOR = "asyncReactionsExecutor";

  /** The start of the line logged when the queue is full; alarms may match on it. */
  public static final String REJECTED_PREFIX = "ASYNC_REJECTED";

  private static final Logger LOG = LoggerFactory.getLogger(AsyncExecution.class);

  @Bean(EXECUTOR)
  ThreadPoolTaskExecutor asyncReactionsExecutor(
      @Value("${pharmacy.async.threads:8}") int threads,
      @Value("${pharmacy.async.queue:10000}") int queue,
      @Value("${pharmacy.async.shutdown-wait:20}") int shutdownWaitSeconds) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("reaction-");
    executor.setCorePoolSize(Math.max(1, threads));
    executor.setMaxPoolSize(Math.max(1, threads));
    executor.setQueueCapacity(Math.max(0, queue));
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(Math.max(0, shutdownWaitSeconds));
    AtomicLong rejected = new AtomicLong();
    executor.setRejectedExecutionHandler(
        (task, pool) -> {
          long count = rejected.incrementAndGet();
          LOG.warn(
              "{} the reactions' queue is full ({} queued, {} threads); the event is delivered"
                  + " again by the event-resubmission job (rejected so far: {})",
              REJECTED_PREFIX,
              pool.getQueue().size(),
              pool.getMaximumPoolSize(),
              count);
          throw new RejectedExecutionException("The reactions' queue is full");
        });
    return executor;
  }

  @Bean
  AsyncConfigurer asyncConfigurer(@Qualifier(EXECUTOR) ThreadPoolTaskExecutor executor) {
    return new AsyncConfigurer() {
      @Override
      public Executor getAsyncExecutor() {
        return executor;
      }

      @Override
      public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (failure, method, params) ->
            LOG.error(
                "An async reaction {}.{} failed; its event is delivered again",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                failure);
      }
    };
  }
}
