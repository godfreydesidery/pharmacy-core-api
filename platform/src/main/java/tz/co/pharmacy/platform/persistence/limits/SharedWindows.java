package tz.co.pharmacy.platform.persistence.limits;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Sliding windows shared by every instance of the API, in PostgreSQL ({@code
 * platform.rate_windows}, platform V8): request throttles ({@link #acquire}) and failure counters
 * that lock a key ({@link #fail}, {@link #lockedFor}). Each key is one row holding its recent hits
 * (a sliding-window log), so a limit is exact at the window's edge, as the in-memory windows were;
 * the database functions lock the row, so concurrent requests on several instances are counted one
 * by one.
 *
 * <p>A key is stored as {@code <kind>:<SHA-256 of the key>}: no email or IP address is kept.
 * Expired rows are deleted by the nightly retention ({@link SharedWindowsRetention}). Times come
 * from the caller, so a simulated clock moves sign-in locks with it.
 */
@Component
public class SharedWindows {

  private final DataSource dataSource;

  public SharedWindows(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Records one request of {@code key}: empty when fewer than {@code limit} fell in the last {@code
   * window} (it is counted), otherwise the seconds until the next one would be allowed (it is not).
   */
  public OptionalLong acquire(String kind, String key, int limit, Duration window, Instant at) {
    int wait =
        query(
            "select platform.rate_acquire(?, ?, ?::interval, ?)",
            statement -> {
              statement.setString(1, stored(kind, key));
              statement.setInt(2, limit);
              statement.setString(3, interval(window));
              statement.setTimestamp(4, Timestamp.from(at));
            },
            result -> result.next() ? result.getInt(1) : 0);
    return wait <= 0 ? OptionalLong.empty() : OptionalLong.of(wait);
  }

  /** How long {@code key} stays locked at {@code at}, or empty when it is not locked. */
  public Optional<Duration> lockedFor(String kind, String key, Instant at) {
    Timestamp until =
        query(
            "select locked_until from platform.rate_windows where key = ? and locked_until > ?",
            statement -> {
              statement.setString(1, stored(kind, key));
              statement.setTimestamp(2, Timestamp.from(at));
            },
            result -> result.next() ? result.getTimestamp(1) : null);
    return until == null ? Optional.empty() : Optional.of(Duration.between(at, until.toInstant()));
  }

  /**
   * Counts a failure of {@code key}: {@code maxFailures} within {@code window} lock it for {@code
   * lockFor}. Returns true when this failure locked it.
   */
  public boolean fail(
      String kind, String key, int maxFailures, Duration window, Duration lockFor, Instant at) {
    return query(
        "select platform.rate_fail(?, ?, ?::interval, ?::interval, ?)",
        statement -> {
          statement.setString(1, stored(kind, key));
          statement.setInt(2, maxFailures);
          statement.setString(3, interval(window));
          statement.setString(4, interval(lockFor));
          statement.setTimestamp(5, Timestamp.from(at));
        },
        result -> result.next() && result.getBoolean(1));
  }

  /** Forgets {@code key} (its hits and any lock). */
  public void clear(String kind, String key) {
    update(
        "delete from platform.rate_windows where key = ?",
        statement -> statement.setString(1, stored(kind, key)));
  }

  /** Deletes the rows with nothing left to remember at {@code now}; returns how many. */
  public int purgeExpired(Instant now, int batch) {
    return update(
        "delete from platform.rate_windows where ctid = any(array("
            + " select ctid from platform.rate_windows where expires_at < ? limit ?))",
        statement -> {
          statement.setTimestamp(1, Timestamp.from(now));
          statement.setInt(2, batch);
        });
  }

  static String stored(String kind, String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((key == null ? "" : key).getBytes(StandardCharsets.UTF_8));
      return kind + ":" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String interval(Duration duration) {
    return duration.toMillis() + " milliseconds";
  }

  // A connection of its own, committed at once, outside the caller's transaction: a counted failure
  // stays counted when the caller's transaction rolls back (a refused sign-in throws), and works
  // under a read-only one.

  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private interface Reader<T> {
    T read(ResultSet result) throws SQLException;
  }

  private <T> T query(String sql, Binder binder, Reader<T> reader) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      try (ResultSet result = statement.executeQuery()) {
        T value = reader.read(result);
        if (!connection.getAutoCommit()) {
          connection.commit();
        }
        return value;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Shared window: " + failure.getMessage(), failure);
    }
  }

  private int update(String sql, Binder binder) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      int n = statement.executeUpdate();
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
      return n;
    } catch (SQLException failure) {
      throw new IllegalStateException("Shared window: " + failure.getMessage(), failure);
    }
  }
}
