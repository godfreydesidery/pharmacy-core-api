package tz.co.pharmacy.platform.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a scheduled job on one instance at a time when several run side by side (several ECS tasks):
 * the job takes a PostgreSQL session advisory lock, {@code pg_try_advisory_lock}, named after the
 * job, on a connection it holds for the run. An instance that finds the lock taken skips this
 * round; the lock goes with the connection, so a crashed instance never leaves it held.
 */
@Component
public class SingleRunner {

  private static final Logger LOG = LoggerFactory.getLogger(SingleRunner.class);

  private final DataSource dataSource;

  public SingleRunner(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** Runs {@code job} unless another instance is running the job of this name; returns whether. */
  public boolean runAlone(String jobName, Runnable job) {
    try (Connection connection = dataSource.getConnection()) {
      if (!lock(connection, "pg_try_advisory_lock", jobName)) {
        LOG.debug("{} is running on another instance; skipped", jobName);
        return false;
      }
      try {
        job.run();
        return true;
      } finally {
        lock(connection, "pg_advisory_unlock", jobName);
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Could not take the lock of " + jobName, failure);
    }
  }

  private static boolean lock(Connection connection, String function, String jobName)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("select " + function + "(hashtextextended(?, 0))")) {
      statement.setString(1, "job:" + jobName);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }
}
