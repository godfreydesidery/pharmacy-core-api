package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tz.co.pharmacy.delivery.api.v1.notifications.NotificationStreams;
import tz.co.pharmacy.delivery.edge.SharedRateLimiter;
import tz.co.pharmacy.modules.access.ports.inbound.AuthenticationService;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppInbox;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.InstanceName;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.jobs.JobRuns;
import tz.co.pharmacy.platform.persistence.limits.SharedWindows;
import tz.co.pharmacy.platform.persistence.signals.PostgresSignals;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Several instances of the API on one database (adversarial review OPS-8, INT-12, AUTH-14; owner's
 * answer 7). The running application is instance A; a second instance B is made of the same parts
 * built a second time (its own job runner, limiter, signal listener connection and notification
 * streams, under another instance name) against the same database, which is all two ECS tasks
 * share.
 */
class MultiInstanceTest extends IntegrationTest {

  private static final Duration MINUTE = Duration.ofMinutes(1);

  @Autowired DataSource dataSource;
  @Autowired SingleRunner runner;
  @Autowired JobRuns jobRuns;
  @Autowired MeterRegistry meters;
  @Autowired SharedRateLimiter limiter;
  @Autowired SharedWindows windows;
  @Autowired Notifier notifier;
  @Autowired InAppInbox inbox;
  @Autowired AuthenticationService authentication;
  @Autowired ApplicationContext context;

  // ------------------------------------------------------------------------------------- jobs

  @Test
  @DisplayName("a job started at the same moment on two instances runs on one; the other skips")
  void jobRunsOnce() throws Exception {
    String job = "multi-" + UUID.randomUUID();
    SingleRunner b = new SingleRunner(dataSource, jobRuns, new InstanceName("instance-b"), meters);
    AtomicInteger ran = new AtomicInteger();
    CyclicBarrier together = new CyclicBarrier(4);
    List<SingleRunner> instances = List.of(runner, b, runner, b);
    List<Boolean> outcomes = new CopyOnWriteArrayList<>();
    ExecutorService threads = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> all = new ArrayList<>();
      for (SingleRunner instance : instances) {
        all.add(
            threads.submit(
                () -> {
                  together.await();
                  outcomes.add(
                      instance.runAlone(
                          job,
                          run -> {
                            ran.incrementAndGet();
                            sleep(1_500);
                          }));
                  return null;
                }));
      }
      for (Future<?> f : all) {
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      threads.shutdownNow();
    }

    assertThat(ran).hasValue(1);
    assertThat(outcomes).containsExactlyInAnyOrder(true, false, false, false);
    List<JobRuns.RunView> runs = jobRuns.recent(job, 10);
    assertThat(runs).hasSize(4);
    assertThat(runs.stream().filter(r -> r.outcome() == JobRuns.Outcome.OK)).hasSize(1);
    assertThat(runs.stream().filter(r -> r.outcome() == JobRuns.Outcome.SKIPPED)).hasSize(3);
    assertThat(runs.stream().map(JobRuns.RunView::instance).distinct()).hasSize(2);
  }

  @Test
  @DisplayName("an SMS claimed by senders on several instances at once is sent once")
  void smsSentOnce() throws Exception {
    String phone = "+2557" + String.valueOf(System.nanoTime()).substring(4, 12);
    Logger provider = (Logger) LoggerFactory.getLogger("notifications.log-provider");
    ListAppender<ILoggingEvent> sends = new ListAppender<>();
    sends.start();
    provider.addAppender(sends);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      platform(
          () ->
              notifier.send(
                  new NotificationRequest(
                      TemplateKeys.SALE_RECEIPT,
                      new Recipient(null, "Customer", null, phone, "en"),
                      EnumSet.of(Channel.SMS),
                      Map.of(
                          "pharmacyName", "Multi Pharmacy",
                          "number", "INV-HQ-000001",
                          "total", "TZS 1,000.00",
                          "date", "today"),
                      null)));
      eventually(() -> "SENT".equals(status(jdbc, phone)));
      assertThat(sendsTo(sends, phone)).isEqualTo(1);
      UUID message =
          jdbc.queryForObject(
              "select uid from notifications.messages where to_address = ?", UUID.class, phone);
      int attempts = attempts(jdbc, phone);

      // Back to QUEUED (its after-commit send never ran, say), due now, and the platform's scope
      // listed as due: every sender of every instance now goes for it at once.
      jdbc.update(
          "update notifications.messages set status = 'QUEUED', next_attempt_at = now()"
              + " - interval '1 second' where uid = ?",
          message);
      jdbc.update(
          "insert into notifications.pending_scopes (scope_uid, next_attempt_at)"
              + " values ('00000000-0000-0000-0000-000000000000', now() - interval '1 second')"
              + " on conflict (scope_uid) do update set next_attempt_at = excluded.next_attempt_at");
      Object dispatcher = AopTestUtils.getUltimateTargetObject(context.getBean("dispatcher"));
      Method deliver = method(dispatcher, "deliver", UUID.class);
      Object retryJob = AopTestUtils.getUltimateTargetObject(context.getBean("retryJob"));
      Method retryDue = method(retryJob, "retryDue");
      CyclicBarrier together = new CyclicBarrier(8);
      ExecutorService threads = Executors.newFixedThreadPool(8);
      try {
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
          boolean job = i < 2;
          all.add(
              threads.submit(
                  () -> {
                    together.await();
                    platform(
                        () -> {
                          try {
                            if (job) {
                              retryDue.invoke(retryJob);
                            } else {
                              deliver.invoke(dispatcher, message);
                            }
                          } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                          }
                        });
                    return null;
                  }));
        }
        for (Future<?> f : all) {
          f.get(60, TimeUnit.SECONDS);
        }
      } finally {
        threads.shutdownNow();
      }
      eventually(() -> "SENT".equals(status(jdbc, phone)));
      assertThat(sendsTo(sends, phone)).isEqualTo(2);
      assertThat(attempts(jdbc, phone)).isEqualTo(attempts + 1);
    } finally {
      provider.detachAppender(sends);
    }
  }

  // ---------------------------------------------------------------------------------- limiters

  @Test
  @DisplayName("the sign-in request limit is one count across instances, exact at the window edge")
  void requestLimitIsShared() {
    SharedRateLimiter b = new SharedRateLimiter(new SharedWindows(dataSource));
    String key = "auth:10.9.0." + UUID.randomUUID();
    Instant t0 = Instant.parse("2026-11-03T08:00:00Z");
    assertThat(limiter.acquire(key, 3, MINUTE, t0)).isEmpty();
    assertThat(b.acquire(key, 3, MINUTE, t0.plusSeconds(1))).isEmpty();
    assertThat(limiter.acquire(key, 3, MINUTE, t0.plusSeconds(2))).isEmpty();
    assertThat(b.acquire(key, 3, MINUTE, t0.plusSeconds(10))).hasValue(50);
    assertThat(b.acquire("auth:other-" + key, 3, MINUTE, t0.plusSeconds(10))).isEmpty();

    // The window slides: the oldest request ageing out frees one slot.
    String sliding = "slide:" + UUID.randomUUID();
    limiter.acquire(sliding, 2, MINUTE, t0);
    b.acquire(sliding, 2, MINUTE, t0.plusSeconds(30));
    assertThat(limiter.acquire(sliding, 2, MINUTE, t0.plusSeconds(59))).isPresent();
    assertThat(b.acquire(sliding, 2, MINUTE, t0.plusSeconds(61))).isEmpty();
    assertThat(limiter.acquire(sliding, 2, MINUTE, t0.plusSeconds(62))).isPresent();
  }

  @Test
  @DisplayName("a failure window locks a key on every instance and forgets it after the lock")
  void failureWindowIsShared() {
    SharedWindows b = new SharedWindows(dataSource);
    Duration window = Duration.ofMinutes(15);
    Duration lock = Duration.ofMinutes(15);
    Instant t0 = Instant.parse("2026-11-03T08:00:00Z");
    String ip = "10.0.0." + UUID.randomUUID();
    assertThat(windows.fail("test-ip", ip, 3, window, lock, t0)).isFalse();
    assertThat(b.fail("test-ip", ip, 3, window, lock, t0.plusSeconds(1))).isFalse();
    assertThat(windows.lockedFor("test-ip", ip, t0.plusSeconds(2))).isEmpty();
    assertThat(b.fail("test-ip", ip, 3, window, lock, t0.plusSeconds(2))).isTrue();
    assertThat(windows.lockedFor("test-ip", ip, t0.plusSeconds(3))).isPresent();
    assertThat(b.lockedFor("test-ip", ip, t0.plusSeconds(3)))
        .hasValueSatisfying(left -> assertThat(left).isEqualTo(lock.minusSeconds(1)));
    assertThat(b.lockedFor("test-ip", "10.0.0.2-" + ip, t0.plusSeconds(3))).isEmpty();
    assertThat(windows.lockedFor("test-ip", ip, t0.plusSeconds(2).plus(lock).plusSeconds(1)))
        .isEmpty();
    // The count started again with the lock.
    assertThat(b.fail("test-ip", ip, 3, window, lock, t0.plus(Duration.ofHours(2)))).isFalse();

    // Failures outside the window do not count.
    String other = "10.0.1." + UUID.randomUUID();
    windows.fail("test-ip", other, 3, window, lock, t0);
    b.fail("test-ip", other, 3, window, lock, t0.plusSeconds(1));
    assertThat(windows.fail("test-ip", other, 3, window, lock, t0.plus(window).plusSeconds(2)))
        .isFalse();

    // No email or address is stored, only a hash of it.
    assertThat(
            new JdbcTemplate(dataSource)
                .queryForObject(
                    "select count(*) from platform.rate_windows where key like ?",
                    Long.class,
                    "%" + ip + "%"))
        .isZero();
  }

  @Test
  @DisplayName("an unknown email locked by failed sign-ins is locked for every instance")
  void unknownEmailLockIsShared() {
    Api anonymous = api();
    String email = "nobody-" + UUID.randomUUID() + "@multi.test";
    for (int i = 0; i < 5; i++) {
      anonymous
          .post(
              "/api/v1/auth/sign-in", "{\"email\":\"" + email + "\",\"password\":\"Wrong#2026x\"}")
          .expect(i < 4 ? 401 : 423);
    }
    anonymous
        .post("/api/v1/auth/sign-in", "{\"email\":\"" + email + "\",\"password\":\"Wrong#2026x\"}")
        .expect(423);
    // Instance B reads the same lock from the database.
    assertThat(new SharedWindows(dataSource).lockedFor("signin-email", email, Instant.now()))
        .isPresent();
    assertThat(meters.get("pharmacy.signin.locks").tag("kind", "unknown_email").counter().count())
        .isPositive();
  }

  // --------------------------------------------------------------------- live notifications

  @Test
  @DisplayName(
      "a message created on instance A reaches a stream open on instance B, also after B's"
          + " listener reconnects")
  void liveNotificationsCrossInstances() {
    PostgresSignals signalsB = new PostgresSignals(dataSource, Duration.ofSeconds(1));
    RecordingStreams b =
        new RecordingStreams(authentication, inbox, signalsB, new InstanceName("instance-b"));
    signalsB.start();
    try {
      eventually(signalsB::connected);
      UUID user = UUID.randomUUID();
      b.open(null, user, null, 0);
      Recording stream = b.emitters.getFirst();
      assertThat(stream.events()).isEmpty();

      inApp(user, 101);
      eventually(() -> stream.events().size() == 1);
      assertThat(stream.events().getFirst().unreadCount()).isEqualTo(1);
      assertThat(stream.events().getFirst().latest().title()).contains("101");

      // The database drops every listener connection (a restart, a failover).
      new JdbcTemplate(dataSource)
          .queryForList(
              "select pg_terminate_backend(pid) from pg_stat_activity"
                  + " where application_name = 'pharmacy-signals'");
      // B reconnects and gives its open streams their current state, then hears new messages.
      eventually(() -> stream.events().size() == 2);
      eventually(signalsB::connected);
      inApp(user, 202);
      eventually(
          () ->
              stream.events().stream()
                  .anyMatch(e -> e.unreadCount() == 2 && e.latest().title().contains("202")));
    } finally {
      signalsB.stop();
    }
  }

  // ------------------------------------------------------------------------------- helpers

  private void inApp(UUID user, int count) {
    platform(
        () ->
            notifier.send(
                new NotificationRequest(
                    TemplateKeys.STOCK_EXPIRING,
                    new Recipient(user, "Asha", "asha-" + user + "@multi.test", null, "en"),
                    EnumSet.of(Channel.IN_APP),
                    Map.of(
                        "count",
                        count,
                        "days",
                        30,
                        "items",
                        "• batch " + count,
                        "severity",
                        "warning",
                        "link",
                        "/inventory/expiry"),
                    null)));
  }

  /** Runs {@code work} as a platform service, at platform scope (no pharmacy). */
  private static void platform(Runnable work) {
    ExecutionContext.callAs(
        ExecutionContext.Frame.service("multi-instance-test", null, null),
        () ->
            TenantContext.callAs(
                null,
                () -> {
                  work.run();
                  return null;
                }));
  }

  private static String status(JdbcTemplate jdbc, String phone) {
    List<String> found =
        jdbc.queryForList(
            "select status from notifications.messages where to_address = ?", String.class, phone);
    return found.isEmpty() ? null : found.getFirst();
  }

  private static int attempts(JdbcTemplate jdbc, String phone) {
    Integer n =
        jdbc.queryForObject(
            "select attempts from notifications.messages where to_address = ?",
            Integer.class,
            phone);
    return n == null ? 0 : n;
  }

  private static long sendsTo(ListAppender<ILoggingEvent> appender, String phone) {
    return appender.list.stream().filter(e -> e.getFormattedMessage().contains(phone)).count();
  }

  private static Method method(Object bean, String name, Class<?>... parameters)
      throws NoSuchMethodException {
    Method method = bean.getClass().getDeclaredMethod(name, parameters);
    method.setAccessible(true);
    return method;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Instance B's streams: what is pushed to each stream is recorded. */
  static final class RecordingStreams extends NotificationStreams {

    final List<Recording> emitters = new CopyOnWriteArrayList<>();

    RecordingStreams(
        AuthenticationService authentication,
        InAppInbox inbox,
        PostgresSignals signals,
        InstanceName instance) {
      super(authentication, inbox, signals, instance);
    }

    @Override
    protected SseEmitter newEmitter(long timeoutMillis) {
      Recording emitter = new Recording(timeoutMillis);
      emitters.add(emitter);
      return emitter;
    }
  }

  /** An emitter that keeps the events sent through it. */
  static final class Recording extends SseEmitter {

    private final List<NotificationStreams.StreamEvent> events = new CopyOnWriteArrayList<>();

    Recording(long timeoutMillis) {
      super(timeoutMillis);
    }

    @Override
    public void send(SseEventBuilder builder) {
      Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
      boolean notification =
          parts.stream()
              .anyMatch(
                  part ->
                      part.getData() instanceof String text && text.contains("event:notification"));
      if (notification) {
        parts.stream()
            .map(ResponseBodyEmitter.DataWithMediaType::getData)
            .filter(NotificationStreams.StreamEvent.class::isInstance)
            .map(NotificationStreams.StreamEvent.class::cast)
            .forEach(events::add);
      }
    }

    List<NotificationStreams.StreamEvent> events() {
      return List.copyOf(events);
    }
  }
}
