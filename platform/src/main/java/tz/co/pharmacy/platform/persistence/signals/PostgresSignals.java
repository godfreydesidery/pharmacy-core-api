package tz.co.pharmacy.platform.persistence.signals;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Signals between the instances of the API through PostgreSQL {@code LISTEN}/{@code NOTIFY}: an
 * instance {@link #publish publishes} a short payload on a channel, and every instance subscribed
 * to the channel (itself included) receives it. Payloads carry ids only, never personal data; the
 * receiver reads what it needs from the database.
 *
 * <p>Each instance holds <b>one dedicated listener connection</b>, outside the connection pool,
 * read by one thread. When that connection breaks (a database restart, a failover, a network drop)
 * the thread reconnects with backoff, listens again and tells every subscriber {@link
 * Subscriber#reconnected()}, since signals sent while it was away are lost (NOTIFY is not queued
 * for a listener that is not connected). A ping every {@code pharmacy.signals.check-every} finds a
 * connection that died silently.
 */
@Component
public class PostgresSignals implements SmartLifecycle {

  /** What a subscriber is told. Called on the listener thread: keep it short. */
  public interface Subscriber {

    void received(String payload);

    /** The listener connection was lost and is back: signals may have been missed meanwhile. */
    default void reconnected() {}
  }

  private static final Logger LOG = LoggerFactory.getLogger(PostgresSignals.class);
  private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  /** NOTIFY's payload limit is 8000 bytes; ours are a few ids. */
  private static final int MAX_PAYLOAD = 1_000;

  private static final int WAIT_MILLIS = 500;

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final Duration checkEvery;
  private final Map<String, List<Subscriber>> subscribers = new ConcurrentHashMap<>();
  private final Set<String> listening = ConcurrentHashMap.newKeySet();

  private volatile boolean running;
  private volatile boolean connected;
  private volatile Thread thread;

  public PostgresSignals(
      DataSource dataSource, @Value("${pharmacy.signals.check-every:PT30S}") Duration checkEvery) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    this.checkEvery = checkEvery;
  }

  /** Subscribes to {@code channel}; the listener thread starts listening on its next turn. */
  public void subscribe(String channel, Subscriber subscriber) {
    requireChannel(channel);
    subscribers.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
  }

  /**
   * Sends {@code payload} to every instance listening on {@code channel}. Inside a transaction it
   * is delivered when the transaction commits (and not at all if it rolls back).
   */
  public void publish(String channel, String payload) {
    requireChannel(channel);
    if (payload != null && payload.length() > MAX_PAYLOAD) {
      throw new IllegalArgumentException("A signal carries ids only");
    }
    jdbc.queryForList("select pg_notify(?, ?)", channel, payload == null ? "" : payload);
  }

  /** Whether the listener connection is up (for tests and health). */
  public boolean connected() {
    return connected;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    running = true;
    thread = Thread.ofPlatform().daemon().name("postgres-signals").start(this::listen);
  }

  @Override
  public void stop() {
    running = false;
    Thread listener = thread;
    if (listener != null) {
      listener.interrupt();
      try {
        listener.join(2_000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void listen() {
    long backoff = 500;
    boolean missed = false;
    while (running) {
      try (Connection connection = open()) {
        listening.clear();
        listenToAll(connection);
        connected = true;
        backoff = 500;
        if (missed) {
          LOG.info("Signal listener reconnected");
          everySubscriber(Subscriber::reconnected);
          missed = false;
        }
        receive(connection);
      } catch (SQLException | RuntimeException failure) {
        connected = false;
        missed = true;
        if (!running) {
          return;
        }
        LOG.warn(
            "Signal listener lost its connection; reconnecting in {} ms: {}",
            backoff,
            failure.toString());
        if (!pause(backoff)) {
          return;
        }
        backoff = Math.min(backoff * 2, 30_000);
      } finally {
        connected = false;
      }
    }
  }

  private void receive(Connection connection) throws SQLException {
    PGConnection pg = connection.unwrap(PGConnection.class);
    long nextCheck = System.nanoTime() + checkEvery.toNanos();
    while (running) {
      listenToAll(connection);
      PGNotification[] notifications = pg.getNotifications(WAIT_MILLIS);
      if (notifications != null) {
        for (PGNotification notification : notifications) {
          deliver(notification.getName(), notification.getParameter());
        }
      }
      if (System.nanoTime() > nextCheck) {
        try (Statement ping = connection.createStatement()) {
          ping.execute("select 1");
        }
        nextCheck = System.nanoTime() + checkEvery.toNanos();
      }
    }
  }

  /** LISTENs to every subscribed channel not listened to yet on this connection. */
  private void listenToAll(Connection connection) throws SQLException {
    for (String channel : subscribers.keySet()) {
      if (listening.add(channel)) {
        try (Statement statement = connection.createStatement()) {
          // The name is checked against CHANNEL: a bare lower-case identifier.
          statement.execute("listen " + channel);
        }
      }
    }
  }

  private void deliver(String channel, String payload) {
    for (Subscriber subscriber : subscribers.getOrDefault(channel, List.of())) {
      try {
        subscriber.received(payload);
      } catch (RuntimeException failure) {
        LOG.warn("A subscriber of {} failed: {}", channel, failure.toString());
      }
    }
  }

  private void everySubscriber(java.util.function.Consumer<Subscriber> call) {
    subscribers.values().stream()
        .flatMap(List::stream)
        .forEach(
            subscriber -> {
              try {
                call.accept(subscriber);
              } catch (RuntimeException failure) {
                LOG.warn("A signal subscriber failed: {}", failure.toString());
              }
            });
  }

  /**
   * A connection of its own (it is held for good, so it does not take one from the pool), made with
   * the pool's settings; without a Hikari pool, one borrowed from the data source.
   */
  private Connection open() throws SQLException {
    HikariDataSource hikari =
        dataSource.isWrapperFor(HikariDataSource.class)
            ? dataSource.unwrap(HikariDataSource.class)
            : null;
    Connection connection;
    if (hikari != null && hikari.getJdbcUrl() != null) {
      Properties properties = new Properties();
      properties.putAll(hikari.getDataSourceProperties());
      if (hikari.getUsername() != null) {
        properties.setProperty("user", hikari.getUsername());
      }
      if (hikari.getPassword() != null) {
        properties.setProperty("password", hikari.getPassword());
      }
      properties.setProperty("ApplicationName", "pharmacy-signals");
      connection = DriverManager.getConnection(hikari.getJdbcUrl(), properties);
    } else {
      connection = dataSource.getConnection();
    }
    connection.setAutoCommit(true);
    return connection;
  }

  private boolean pause(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void requireChannel(String channel) {
    if (channel == null || !CHANNEL.matcher(channel).matches()) {
      throw new IllegalArgumentException("Not a channel name: " + channel);
    }
  }
}
