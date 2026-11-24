package tz.co.pharmacy.modules.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The notification senders are bounded: threads, a queue, and a dropped dispatch is counted. */
class NotificationSendersTest {

  @Test
  @DisplayName("past its threads and queue a dispatch is dropped and counted, never run inline")
  void boundedWithBackPressure() throws InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ThreadPoolExecutor senders = NotificationsConfiguration.senders(2, 3, meters);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(2);
    AtomicInteger ranOnCaller = new AtomicInteger();
    Thread caller = Thread.currentThread();
    try {
      for (int i = 0; i < 2; i++) {
        senders.submit(
            () -> {
              started.countDown();
              release.await();
              return null;
            });
      }
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      // Three wait in the queue; the next two find it full.
      for (int i = 0; i < 5; i++) {
        assertThatCode(
                () ->
                    senders.submit(
                        () -> {
                          if (Thread.currentThread() == caller) {
                            ranOnCaller.incrementAndGet();
                          }
                        }))
            .doesNotThrowAnyException();
      }
      assertThat(senders.getMaximumPoolSize()).isEqualTo(2);
      assertThat(meters.get("pharmacy.notifications.senders.queued").gauge().value())
          .isEqualTo(3.0);
      assertThat(meters.get("pharmacy.notifications.senders.active").gauge().value())
          .isEqualTo(2.0);
      assertThat(meters.get("pharmacy.notifications.senders.rejected").counter().count())
          .isEqualTo(2.0);
      assertThat(ranOnCaller).hasValue(0);
    } finally {
      release.countDown();
      senders.shutdown();
      assertThat(senders.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
