package tz.co.pharmacy;

import java.time.ZoneOffset;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import tz.co.pharmacy.platform.composition.LocalDatabase;

/**
 * The Pharmacy Core modulith: one deployable, many modules, each with its own schema. It runs in
 * UTC; clients present times in the tenant's timezone. With the {@code local} profile it first
 * starts an embedded PostgreSQL ({@link LocalDatabase}), so it runs with nothing installed.
 */
@Modulithic(systemName = "Pharmacy Core")
@SpringBootApplication
public class PharmacyApplication {

  public static void main(String[] arguments) {
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
    LocalDatabase.startIfRequested(arguments);
    SpringApplication.run(PharmacyApplication.class, arguments);
  }
}
