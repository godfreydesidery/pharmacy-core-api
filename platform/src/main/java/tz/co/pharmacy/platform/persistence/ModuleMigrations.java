package tz.co.pharmacy.platform.persistence;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;

/**
 * Runs one Flyway per schema, in a fixed order, each with its own history table in its schema. A
 * module's migrations sit in its own Maven module under {@code db/migration/<schema>}, so a module
 * and its store can leave together. Migrations run as the owner role; the application role only
 * receives grants ({@code ${application_role}}) and never owns a table or bypasses row-level
 * security.
 */
public class ModuleMigrations {

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

  private final String url;
  private final String ownerUsername;
  private final String ownerPassword;
  private final String applicationRole;

  public ModuleMigrations(
      String url, String ownerUsername, String ownerPassword, String applicationRole) {
    this.url = url;
    this.ownerUsername = ownerUsername;
    this.ownerPassword = ownerPassword;
    this.applicationRole = applicationRole;
  }

  public void migrate() {
    for (String schema : SCHEMAS) {
      Flyway.configure()
          .dataSource(url, ownerUsername, ownerPassword)
          .schemas(schema)
          .defaultSchema(schema)
          .createSchemas(true)
          .initSql("SET TIME ZONE 'UTC'")
          .table("flyway_schema_history")
          .locations("classpath:db/migration/" + schema)
          .placeholders(Map.of("application_role", applicationRole))
          .load()
          .migrate();
    }
  }
}
