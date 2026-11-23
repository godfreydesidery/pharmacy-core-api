package tz.co.pharmacy;

import java.time.ZoneOffset;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import tz.co.pharmacy.platform.composition.LocalDatabase;
import tz.co.pharmacy.platform.persistence.MigrateOnly;
import tz.co.pharmacy.platform.persistence.MigrationMode;

/**
 * The Pharmacy Core modulith: one deployable, many modules, each with its own schema. It runs in
 * UTC; clients present times in the tenant's timezone. With the {@code local} profile it first
 * starts an embedded PostgreSQL ({@link LocalDatabase}), so it runs with nothing installed. With
 * {@code PHARMACY_MIGRATIONS_MODE=migrate-only} it only migrates the schemas and exits ({@link
 * MigrateOnly}).
 */
@Modulithic(systemName = "Pharmacy Core")
@SpringBootApplication
public class PharmacyApplication {

  public static void main(String[] arguments) {
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
    LocalDatabase.startIfRequested(arguments);
    if (MigrationMode.migrateOnlyRequested(arguments)) {
      // The one-off migration task: migrate as the owner role, then exit (0 when done).
      System.exit(MigrateOnly.run(arguments));
    }
    SpringApplication.run(PharmacyApplication.class, arguments);
  }
}
