package tz.co.pharmacy.platform.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Runs one Flyway per schema, in a fixed order, each with its own history table in its schema. A
 * module's migrations sit in its own Maven module under {@code db/migration/<schema>}, so a module
 * and its store can leave together. Migrations run as the owner role; the application role only
 * receives grants ({@code ${application_role}}) and never owns a table or bypasses row-level
 * security.
 *
 * <p>Every migration connection waits at most {@code lock_timeout} (5 s) for a lock and runs a
 * statement at most {@code statement_timeout} (5 min) (OPS-10): a migration that queues behind a
 * long transaction of the running API fails fast instead of stalling every query queued behind its
 * lock, and PostgreSQL rolls it back (Flyway runs each migration in its own transaction). Run the
 * migration again once the database is quieter; see the runbook "Migrations".
 *
 * <p>The schemas migrate one after the other: when one fails, the schemas before it are complete,
 * it stays at its last applied version, and the schemas after it are untouched. Migrating again
 * continues from there.
 *
 * <p>After each schema, the application role may read its history table, so an API in {@code
 * run-only} mode checks, without the owner's credentials, that no migration is pending ({@link
 * #pending}).
 */
public class ModuleMigrations {

  private static final Logger LOG = LoggerFactory.getLogger(ModuleMigrations.class);

  /** Each schema in migration order. The platform helpers come first: every module calls them. */
  public static final List<String> SCHEMAS =
      List.of(
          "platform",
          "audit",
          "tenancy",
          "access",
          "catalogue",
          "partners",
          "inventory",
          "purchasing",
          "sales",
          "finance",
          "reporting",
          "integrations",
          "fx",
          "notifications",
          "files",
          "receivables",
          "insurance");

  public static final String HISTORY_TABLE = "flyway_schema_history";
  public static final String DEFAULT_LOCK_TIMEOUT = "5s";
  public static final String DEFAULT_STATEMENT_TIMEOUT = "5min";

  private static final Pattern DURATION = Pattern.compile("\\d+(ms|s|min|h)?");
  private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

  private final String url;
  private final String ownerUsername;
  private final String ownerPassword;
  private final String applicationRole;
  private final String lockTimeout;
  private final String statementTimeout;

  public ModuleMigrations(
      String url, String ownerUsername, String ownerPassword, String applicationRole) {
    this(
        url,
        ownerUsername,
        ownerPassword,
        applicationRole,
        DEFAULT_LOCK_TIMEOUT,
        DEFAULT_STATEMENT_TIMEOUT);
  }

  public ModuleMigrations(
      String url,
      String ownerUsername,
      String ownerPassword,
      String applicationRole,
      String lockTimeout,
      String statementTimeout) {
    this.url = url;
    this.ownerUsername = ownerUsername;
    this.ownerPassword = ownerPassword;
    this.applicationRole = applicationRole;
    this.lockTimeout = duration("pharmacy.migrations.lock-timeout", lockTimeout);
    this.statementTimeout = duration("pharmacy.migrations.statement-timeout", statementTimeout);
  }

  /**
   * The migrations the environment describes: {@code spring.datasource.url}, the owner role ({@code
   * pharmacy.database.owner-username} and {@code -password}), the application role's name ({@code
   * spring.datasource.username}; its password is not needed) and the timeouts ({@code
   * pharmacy.migrations.lock-timeout}, {@code pharmacy.migrations.statement-timeout}).
   */
  public static ModuleMigrations from(Environment environment) {
    return new ModuleMigrations(
        required(environment, "spring.datasource.url"),
        required(environment, "pharmacy.database.owner-username"),
        environment.getProperty("pharmacy.database.owner-password", ""),
        required(environment, "spring.datasource.username"),
        environment.getProperty("pharmacy.migrations.lock-timeout", DEFAULT_LOCK_TIMEOUT),
        environment.getProperty(
            "pharmacy.migrations.statement-timeout", DEFAULT_STATEMENT_TIMEOUT));
  }

  /** The Flyway of one schema, as the owner, with the timeouts on every connection. */
  public FluentConfiguration configure(String schema) {
    return Flyway.configure()
        .dataSource(url, ownerUsername, ownerPassword)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .initSql(initSql())
        .table(HISTORY_TABLE)
        .locations("classpath:db/migration/" + schema)
        .placeholders(Map.of("application_role", applicationRole));
  }

  /** The settings of every migration connection. */
  public String initSql() {
    return "SET TIME ZONE 'UTC'; SET lock_timeout = '"
        + lockTimeout
        + "'; SET statement_timeout = '"
        + statementTimeout
        + "'";
  }

  public void migrate() {
    for (String schema : SCHEMAS) {
      MigrateResult result = configure(schema).load().migrate();
      if (result.migrationsExecuted > 0) {
        LOG.info(
            "Schema {}: {} migrations applied, now at version {}",
            schema,
            result.migrationsExecuted,
            result.targetSchemaVersion);
      }
      grantHistory(schema);
    }
  }

  /** Lets the application role read the schema's history, for the run-only check. */
  private void grantHistory(String schema) {
    try (Connection connection = DriverManager.getConnection(url, ownerUsername, ownerPassword);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "grant select on "
              + identifier(schema)
              + "."
              + HISTORY_TABLE
              + " to "
              + identifier(applicationRole));
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Could not let " + applicationRole + " read " + schema + "." + HISTORY_TABLE, failure);
    }
  }

  /**
   * What stops the API in {@code run-only} mode: each schema with a migration this build has and
   * the database has not applied, or whose applied migrations differ from this build's, read
   * through {@code dataSource} (the application role). Migrations the database has and this build
   * does not (a newer release migrated already) are allowed: the previous release keeps running
   * during a rolling deploy.
   */
  public static List<String> pending(DataSource dataSource, String applicationRole) {
    List<String> problems = new ArrayList<>();
    for (String schema : SCHEMAS) {
      try {
        ValidateResult result =
            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .table(HISTORY_TABLE)
                .locations("classpath:db/migration/" + schema)
                .placeholders(Map.of("application_role", applicationRole))
                .load()
                .validateWithResult();
        if (!result.validationSuccessful) {
          problems.add(schema + ": " + oneLine(result.getAllErrorMessages()));
        }
      } catch (FlywayException failure) {
        problems.add(schema + ": " + oneLine(failure.getMessage()));
      }
    }
    return problems;
  }

  private static String oneLine(String text) {
    return text == null ? "not readable" : text.replaceAll("\\s+", " ").trim();
  }

  private static String identifier(String name) {
    return PLAIN_IDENTIFIER.matcher(name).matches() ? name : '"' + name.replace("\"", "\"\"") + '"';
  }

  private static String duration(String property, String value) {
    String trimmed = value == null ? "" : value.trim();
    if (!DURATION.matcher(trimmed).matches()) {
      throw new IllegalStateException(
          property + " is '" + value + "': use a PostgreSQL duration such as 5s, 500ms or 10min.");
    }
    return trimmed;
  }

  private static String required(Environment environment, String name) {
    String value = environment.getProperty(name, "");
    if (value.isBlank()) {
      throw new IllegalStateException(name + " must be set to migrate the schemas");
    }
    return value;
  }
}
