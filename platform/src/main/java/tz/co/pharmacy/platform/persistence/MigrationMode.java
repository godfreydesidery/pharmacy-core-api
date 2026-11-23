package tz.co.pharmacy.platform.persistence;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * How a process deals with the schema migrations ({@code pharmacy.migrations.mode}, {@code
 * PHARMACY_MIGRATIONS_MODE}):
 *
 * <ul>
 *   <li>{@code migrate-and-run} (the default): migrate as the owner role at start, then serve. For
 *       a laptop and a single-service deployment (Railway);
 *   <li>{@code migrate-only}: migrate every schema as the owner role and exit 0 (1 on failure),
 *       serving nothing. The one-off task that runs before a deploy (an ECS task);
 *   <li>{@code run-only}: never migrate and never hold the owner credentials; refuse to start while
 *       any schema has a migration this build knows and the database has not applied (read as the
 *       application role). The API service behind the load balancer.
 * </ul>
 *
 * The older {@code pharmacy.database.migrations-enabled=false} still means {@code run-only}.
 */
public enum MigrationMode {
  MIGRATE_AND_RUN("migrate-and-run"),
  MIGRATE_ONLY("migrate-only"),
  RUN_ONLY("run-only");

  public static final String PROPERTY = "pharmacy.migrations.mode";
  public static final String VARIABLE = "PHARMACY_MIGRATIONS_MODE";

  private final String id;

  MigrationMode(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  /** The mode the environment sets. */
  public static MigrationMode of(Environment environment) {
    String value = environment.getProperty(PROPERTY, "");
    if (value.isBlank()) {
      boolean enabled =
          !"false"
              .equalsIgnoreCase(
                  environment.getProperty("pharmacy.database.migrations-enabled", "true").trim());
      return enabled ? MIGRATE_AND_RUN : RUN_ONLY;
    }
    return parse(value);
  }

  public static MigrationMode parse(String value) {
    String wanted = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    for (MigrationMode mode : values()) {
      if (mode.id.equals(wanted)) {
        return mode;
      }
    }
    throw new IllegalStateException(
        PROPERTY
            + " ("
            + VARIABLE
            + ") is '"
            + value
            + "': use migrate-and-run, migrate-only or run-only.");
  }

  /**
   * Whether the process was launched to migrate only, as the launcher sees it before Spring starts:
   * a {@code --pharmacy.migrations.mode=migrate-only} argument, the system property or the
   * environment variable.
   */
  public static boolean migrateOnlyRequested(String[] arguments) {
    String value = null;
    for (String argument : arguments) {
      if (argument.startsWith("--" + PROPERTY + "=")) {
        value = argument.substring(PROPERTY.length() + 3);
      }
    }
    if (value == null) {
      value = System.getProperty(PROPERTY);
    }
    if (value == null) {
      value = System.getenv(VARIABLE);
    }
    return value != null && !value.isBlank() && parse(value) == MIGRATE_ONLY;
  }
}
