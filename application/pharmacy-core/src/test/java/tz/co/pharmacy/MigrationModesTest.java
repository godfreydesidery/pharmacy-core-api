package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import tz.co.pharmacy.platform.persistence.MigrateOnly;
import tz.co.pharmacy.platform.persistence.MigrationMode;
import tz.co.pharmacy.platform.persistence.MigrationsBeforeJpa;
import tz.co.pharmacy.platform.persistence.ModuleMigrations;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * The migration modes (OPS-11) and the migration lock timeout (OPS-10), each on a database of its
 * own in the suite's embedded PostgreSQL: {@code migrate-only} migrates with the owner's
 * credentials alone and exits 0; {@code run-only} needs no owner credentials and refuses to start
 * while a migration is pending; a migration that waits on a lock gives up and is rolled back.
 */
class MigrationModesTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${spring.datasource.username}")
  String appRole;

  @Value("${spring.datasource.password}")
  String appPassword;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private String database;
  private String databaseUrl;

  @BeforeEach
  void freshDatabase() throws SQLException {
    database = "modes_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (Connection connection = superuser();
        Statement statement = connection.createStatement()) {
      statement.execute("create database " + database + " owner " + owner);
    }
    databaseUrl = url.substring(0, url.lastIndexOf('/') + 1) + database;
  }

  @AfterEach
  void dropDatabase() throws SQLException {
    try (Connection connection = superuser();
        Statement statement = connection.createStatement()) {
      statement.execute("drop database if exists " + database + " with (force)");
    }
  }

  @Test
  @DisplayName("migrate-only migrates every schema with the owner's credentials alone, and exits 0")
  void migrateOnly() {
    assertThat(migrateOnly(ownerPassword)).isZero();
    assertThat(ModuleMigrations.pending(applicationRole(), appRole)).isEmpty();
    // Again: nothing to do, still 0.
    assertThat(migrateOnly(ownerPassword)).isZero();
  }

  @Test
  @DisplayName("migrate-only exits 1 when it cannot migrate")
  void migrateOnlyFails() {
    // No such database: nothing can migrate (the embedded server trusts local passwords).
    databaseUrl = databaseUrl + "_missing";
    assertThat(migrateOnly(ownerPassword)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "run-only holds no owner credentials and refuses to start while a migration is pending")
  void runOnly() throws SQLException {
    MigrationsBeforeJpa runOnly = runOnlyApi();
    DataSource dataSource = applicationRole();
    assertThatThrownBy(() -> runOnly.postProcessAfterInitialization(dataSource, "dataSource"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("migrate-only")
        .hasMessageContaining("platform:");

    assertThat(migrateOnly(ownerPassword)).isZero();
    assertThat(runOnlyApi().postProcessAfterInitialization(dataSource, "dataSource"))
        .isSameAs(dataSource);

    // One schema behind (its newest migration not applied): refused, naming the schema.
    try (Connection connection = DriverManager.getConnection(databaseUrl, owner, ownerPassword);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "delete from insurance.flyway_schema_history where installed_rank ="
              + " (select max(installed_rank) from insurance.flyway_schema_history)");
    }
    assertThatThrownBy(() -> runOnlyApi().postProcessAfterInitialization(dataSource, "dataSource"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("insurance:")
        .hasMessageNotContaining("platform:");
  }

  @Test
  @DisplayName(
      "a migration waiting on a lock gives up after lock_timeout and is rolled back; it runs once"
          + " the lock is gone")
  void lockTimeout() throws SQLException {
    ModuleMigrations migrations =
        new ModuleMigrations(databaseUrl, owner, ownerPassword, appRole, "1s", "5min");
    migrations.configure("lockprobe").target("1").load().migrate();
    try (Connection connection = DriverManager.getConnection(databaseUrl, owner, ownerPassword);
        Statement statement = connection.createStatement();
        ResultSet settings =
            statement.executeQuery(
                "select lock_timeout, statement_timeout from lockprobe.settings")) {
      assertThat(settings.next()).isTrue();
      assertThat(settings.getString(1)).isEqualTo("1s");
      assertThat(settings.getString(2)).isEqualTo("5min");
    }

    try (Connection reader = DriverManager.getConnection(databaseUrl, owner, ownerPassword)) {
      reader.setAutoCommit(false);
      try (Statement statement = reader.createStatement()) {
        statement.execute("lock table lockprobe.busy in access share mode");
      }
      long started = System.nanoTime();
      assertThatThrownBy(() -> migrations.configure("lockprobe").load().migrate())
          .isInstanceOf(FlywayException.class)
          .hasStackTraceContaining("lock timeout");
      assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(30_000);
      reader.rollback();
    }
    assertThat(columns()).doesNotContain("waited");
    assertThat(appliedVersions()).containsExactly("1");

    migrations.configure("lockprobe").load().migrate();
    assertThat(columns()).contains("waited");
    assertThat(appliedVersions()).containsExactly("1", "2");
  }

  @Test
  @DisplayName(
      "the mode comes from pharmacy.migrations.mode, or the older migrations-enabled switch")
  void modes() {
    assertThat(MigrationMode.of(new MockEnvironment())).isEqualTo(MigrationMode.MIGRATE_AND_RUN);
    assertThat(
            MigrationMode.of(
                new MockEnvironment()
                    .withProperty("pharmacy.database.migrations-enabled", "false")))
        .isEqualTo(MigrationMode.RUN_ONLY);
    assertThat(
            MigrationMode.of(
                new MockEnvironment()
                    .withProperty("pharmacy.migrations.mode", "migrate-only")
                    .withProperty("pharmacy.database.migrations-enabled", "false")))
        .isEqualTo(MigrationMode.MIGRATE_ONLY);
    assertThat(MigrationMode.parse("RUN_ONLY")).isEqualTo(MigrationMode.RUN_ONLY);
    assertThatThrownBy(() -> MigrationMode.parse("sometimes"))
        .hasMessageContaining("PHARMACY_MIGRATIONS_MODE");
    assertThat(
            MigrationMode.migrateOnlyRequested(
                new String[] {"--pharmacy.migrations.mode=migrate-only"}))
        .isTrue();
    assertThat(
            MigrationMode.migrateOnlyRequested(
                new String[] {"--pharmacy.migrations.mode=run-only"}))
        .isFalse();
  }

  private int migrateOnly(String password) {
    String previous = System.getProperty(LoggingSystem.SYSTEM_PROPERTY);
    // Leave the running suite's logging as it is.
    System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
    try {
      return MigrateOnly.run(
          "--spring.datasource.url=" + databaseUrl,
          "--spring.datasource.username=" + appRole,
          "--pharmacy.database.owner-username=" + owner,
          "--pharmacy.database.owner-password=" + password);
    } finally {
      if (previous == null) {
        System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
      } else {
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, previous);
      }
    }
  }

  /** run-only, as an API service holds it: the application role, no owner credentials at all. */
  private MigrationsBeforeJpa runOnlyApi() {
    MigrationsBeforeJpa migrations = new MigrationsBeforeJpa();
    migrations.setEnvironment(
        new MockEnvironment()
            .withProperty("pharmacy.migrations.mode", "run-only")
            .withProperty("spring.datasource.url", databaseUrl)
            .withProperty("spring.datasource.username", appRole));
    return migrations;
  }

  private DataSource applicationRole() {
    return new DriverManagerDataSource(databaseUrl, appRole, appPassword);
  }

  private List<String> columns() throws SQLException {
    return strings(
        "select column_name from information_schema.columns"
            + " where table_schema = 'lockprobe' and table_name = 'busy' order by ordinal_position");
  }

  private List<String> appliedVersions() throws SQLException {
    return strings(
        "select version from lockprobe.flyway_schema_history where success and version is not null"
            + " order by installed_rank");
  }

  private List<String> strings(String sql) throws SQLException {
    try (Connection connection = DriverManager.getConnection(databaseUrl, owner, ownerPassword);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      List<String> values = new java.util.ArrayList<>();
      while (rows.next()) {
        values.add(rows.getString(1));
      }
      return values;
    }
  }

  private Connection superuser() throws SQLException {
    String server = url.substring(0, url.lastIndexOf('/') + 1) + "postgres";
    return DriverManager.getConnection(server, "postgres", "");
  }
}
