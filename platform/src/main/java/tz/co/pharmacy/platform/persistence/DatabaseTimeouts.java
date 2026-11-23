package tz.co.pharmacy.platform.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * The time limits of every connection of the application's pool (adversarial review PERF-2), set on
 * the connection when the pool opens it (Hikari's {@code connectionInitSql}), because the
 * application role cannot {@code ALTER ROLE} itself:
 *
 * <ul>
 *   <li>{@code statement_timeout} ({@code pharmacy.database.statement-timeout}, 30 s): a statement
 *       that runs longer is cancelled (SQLState 57014), so one slow query cannot hold a connection
 *       and its locks for minutes. A transaction declared with a timeout
 *       ({@code @Transactional(timeout = …)}) and a scheduled job ({@link SingleRunner}) raise it
 *       for their own transactions with {@code SET LOCAL} ({@link TenantAwareTransactionManager});
 *   <li>{@code lock_timeout} ({@code pharmacy.database.lock-timeout}, 5 s): a statement that waits
 *       longer for a row or table lock fails (55P03), which the API answers 409 busy_try_again;
 *   <li>{@code idle_in_transaction_session_timeout} ({@code
 *       pharmacy.database.idle-in-transaction-timeout}, 60 s): a session left idle inside an open
 *       transaction (a bug, a hung client of the pool) is ended, and with it the locks it holds.
 * </ul>
 *
 * None of them touches a connection that is idle outside a transaction: the job lock's connection
 * ({@link SingleRunner} holds a session advisory lock in auto-commit, idle between its two
 * statements) and the pool's idle connections keep running. The LISTEN connection of {@link
 * tz.co.pharmacy.platform.persistence.signals.PostgresSignals} is opened outside the pool and gets
 * none of them. {@code idle_session_timeout} is never set, as it would end both. A value of 0 turns
 * a limit off.
 */
public class DatabaseTimeouts implements BeanPostProcessor, EnvironmentAware {

  private static final Logger LOG = LoggerFactory.getLogger(DatabaseTimeouts.class);

  /** PostgreSQL's units for a time setting; a bare number is milliseconds. */
  private static final Pattern DURATION = Pattern.compile("\\d+(ms|s|min|h)?");

  public static final String STATEMENT = "pharmacy.database.statement-timeout";
  public static final String LOCK = "pharmacy.database.lock-timeout";
  public static final String IDLE_IN_TRANSACTION = "pharmacy.database.idle-in-transaction-timeout";

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  /** After Spring Boot bound {@code spring.datasource.hikari.*}, before the pool opens. */
  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof HikariDataSource hikari) {
      String settings = settings(environment);
      String given = hikari.getConnectionInitSql();
      hikari.setConnectionInitSql(
          given == null || given.isBlank() ? settings : given.trim() + "; " + settings);
      LOG.info("Database connections start with: {}", settings);
    }
    return bean;
  }

  /** The SET statements every pooled connection runs when it is opened. */
  public static String settings(Environment environment) {
    return "SET statement_timeout = '"
        + value(environment, STATEMENT, "30s")
        + "'; SET lock_timeout = '"
        + value(environment, LOCK, "5s")
        + "'; SET idle_in_transaction_session_timeout = '"
        + value(environment, IDLE_IN_TRANSACTION, "60s")
        + "'";
  }

  private static String value(Environment environment, String property, String fallback) {
    String value = environment.getProperty(property, fallback).trim();
    if (!DURATION.matcher(value).matches()) {
      throw new IllegalStateException(
          property + " must be a PostgreSQL duration such as 30s, 500ms or 2min, not: " + value);
    }
    return value;
  }
}
