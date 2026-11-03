package tz.co.pharmacy.platform.composition;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;

/**
 * The embedded PostgreSQL of the zero-setup local run (profile {@code local}) and of the tests. It
 * holds the same two roles as production: {@code pharmacy_owner} owns the schemas and runs the
 * migrations; {@code pharmacy_app} neither owns them nor bypasses row-level security, so tenant
 * isolation applies locally exactly as in production.
 */
public final class LocalDatabase {

  public static final String DATABASE = "pharmacy";
  public static final String OWNER_ROLE = "pharmacy_owner";
  public static final String APPLICATION_ROLE = "pharmacy_app";
  private static final String OWNER_PASSWORD = "local-owner-password";
  private static final String APPLICATION_PASSWORD = "local-app-password";

  private static EmbeddedPostgres server;

  private LocalDatabase() {}

  /** Starts the database and points Spring at it when the local profile is active. */
  public static void startIfRequested(String[] arguments) {
    String fromArguments =
        String.join(
            ",",
            Arrays.stream(arguments)
                .filter(argument -> argument.startsWith("--spring.profiles.active="))
                .map(argument -> argument.substring("--spring.profiles.active=".length()))
                .toList());
    String profiles =
        String.join(
            ",",
            System.getProperty("spring.profiles.active", ""),
            System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", ""),
            fromArguments);
    if (Arrays.asList(profiles.split(",")).contains("local")) {
      Path directory =
          Path.of(System.getenv().getOrDefault("PHARMACY_LOCAL_DATA", "target/local-postgres"));
      int port = Integer.parseInt(System.getenv().getOrDefault("PHARMACY_LOCAL_DB_PORT", "54330"));
      start(directory, port).forEach(System::setProperty);
    }
  }

  /** Starts (or reuses) the server and returns the Spring properties that connect to it. */
  public static synchronized Map<String, String> start(Path dataDirectory, int port) {
    if (server == null && !listening(port)) {
      try {
        Files.createDirectories(dataDirectory);
        server =
            EmbeddedPostgres.builder()
                .setDataDirectory(dataDirectory.toFile())
                .setCleanDataDirectory(false)
                .setPort(port)
                .start();
      } catch (IOException exception) {
        throw new UncheckedIOException("could not start the local PostgreSQL", exception);
      }
    }
    prepare(port);
    String url = "jdbc:postgresql://localhost:" + port + "/" + DATABASE;
    return Map.of(
        "spring.datasource.url", url,
        "spring.datasource.username", APPLICATION_ROLE,
        "spring.datasource.password", APPLICATION_PASSWORD,
        "pharmacy.database.owner-username", OWNER_ROLE,
        "pharmacy.database.owner-password", OWNER_PASSWORD);
  }

  private static boolean listening(int port) {
    try (Connection ignored = superuser(port)) {
      return true;
    } catch (SQLException nothing) {
      return false;
    }
  }

  private static Connection superuser(int port) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://localhost:" + port + "/postgres?connectTimeout=2", "postgres", "");
  }

  private static void prepare(int port) {
    try (Connection connection = superuser(port);
        Statement statement = connection.createStatement()) {
      if (!exists(statement, "select 1 from pg_roles where rolname = '" + OWNER_ROLE + "'")) {
        statement.execute("create role " + OWNER_ROLE + " login password '" + OWNER_PASSWORD + "'");
      }
      if (!exists(statement, "select 1 from pg_roles where rolname = '" + APPLICATION_ROLE + "'")) {
        statement.execute(
            "create role "
                + APPLICATION_ROLE
                + " login nosuperuser nobypassrls password '"
                + APPLICATION_PASSWORD
                + "'");
      }
      if (!exists(statement, "select 1 from pg_database where datname = '" + DATABASE + "'")) {
        statement.execute("create database " + DATABASE + " owner " + OWNER_ROLE);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("could not prepare the local PostgreSQL", exception);
    }
  }

  private static boolean exists(Statement statement, String query) throws SQLException {
    try (ResultSet rows = statement.executeQuery(query)) {
      return rows.next();
    }
  }
}
