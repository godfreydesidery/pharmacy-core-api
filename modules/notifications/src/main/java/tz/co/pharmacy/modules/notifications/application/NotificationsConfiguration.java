package tz.co.pharmacy.modules.notifications.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The senders that deliver messages after commit, away from requests and listeners. Before Batch 10
 * every dispatch took a virtual thread of its own with nothing to bound them, so a burst (a
 * pharmacy's daily digests, a provider that hangs) could open as many SMTP and HTTP connections,
 * and database transactions, as there were messages (adversarial review, Batch 7 "still open").
 *
 * <p>Now a fixed pool of {@code pharmacy.notifications.senders.threads} (8) takes dispatches in
 * order from a queue of at most {@code pharmacy.notifications.senders.queue} (2,000). When the
 * queue is full a dispatch is <b>dropped, not run by the caller</b> (it runs after a commit, on a
 * request's thread): logged {@code NOTIFY_REJECTED} and counted. Nothing is lost: its messages are
 * already committed as QUEUED with their scope due within a minute, so the {@code
 * notification-retries} job sends them (only a live in-app push is skipped; the inbox item is
 * there). Meters: {@code pharmacy.notifications.senders.queued} and {@code .active} (gauges) and
 * {@code pharmacy.notifications.senders.rejected} (a counter).
 */
@Configuration(proxyBeanMethods = false)
class NotificationsConfiguration {

  /** The start of the line logged when a dispatch is dropped; alarms may match on it. */
  static final String REJECTED_PREFIX = "NOTIFY_REJECTED";

  private static final Logger log = LoggerFactory.getLogger(NotificationsConfiguration.class);

  @Bean(destroyMethod = "shutdown")
  ExecutorService notificationSenders(
      @Value("${pharmacy.notifications.senders.threads:8}") int threads,
      @Value("${pharmacy.notifications.senders.queue:2000}") int queue,
      ObjectProvider<MeterRegistry> meters) {
    return senders(threads, queue, meters.getIfAvailable(SimpleMeterRegistry::new));
  }

  /** A bounded pool of {@code threads} virtual threads and a queue of {@code queue} dispatches. */
  static ThreadPoolExecutor senders(int threads, int queue, MeterRegistry meters) {
    int size = Math.max(1, threads);
    Counter rejected =
        Counter.builder("pharmacy.notifications.senders.rejected")
            .description("Dispatches dropped because the senders' queue was full (retried later)")
            .register(meters);
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            size,
            size,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(1, queue)),
            Thread.ofVirtual().name("notify-", 0).factory(),
            (task, executor) -> {
              rejected.increment();
              log.warn(
                  "{} the senders' queue is full ({} queued, {} threads); its messages stay"
                      + " queued and the notification-retries job sends them (dropped so far: {})",
                  REJECTED_PREFIX,
                  executor.getQueue().size(),
                  executor.getMaximumPoolSize(),
                  (long) rejected.count());
            });
    Gauge.builder("pharmacy.notifications.senders.queued", pool, p -> p.getQueue().size())
        .description("Dispatches waiting for a sender")
        .register(meters);
    Gauge.builder("pharmacy.notifications.senders.active", pool, ThreadPoolExecutor::getActiveCount)
        .description("Senders delivering right now")
        .register(meters);
    return pool;
  }
}
