package tz.co.pharmacy.platform.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code migrate-only} mode (OPS-11): reads the configuration as the API would (application
 * properties, profiles, environment variables), migrates every schema as the owner role, and
 * returns the process's exit code: 0 when every schema is at the latest version, 1 otherwise. It
 * starts no web server, no connection pool and no other bean, so the one-off task needs only the
 * database URL, the application role's name and the owner's credentials.
 */
public final class MigrateOnly {

  private static final Logger LOG = LoggerFactory.getLogger(MigrateOnly.class);

  private MigrateOnly() {}

  /** Migrates and returns the exit code; the launcher exits with it. */
  public static int run(String... arguments) {
    ConfigurableApplicationContext context = null;
    try {
      context =
          new SpringApplicationBuilder(Settings.class)
              .web(WebApplicationType.NONE)
              .bannerMode(Banner.Mode.OFF)
              .logStartupInfo(false)
              .registerShutdownHook(false)
              .run(arguments);
      long started = System.nanoTime();
      ModuleMigrations.from(context.getEnvironment()).migrate();
      LOG.info(
          "migrate-only: all {} schemas are at the latest version ({} ms)",
          ModuleMigrations.SCHEMAS.size(),
          (System.nanoTime() - started) / 1_000_000);
      return 0;
    } catch (RuntimeException failure) {
      LOG.error("migrate-only failed: {}", failure.getMessage(), failure);
      return 1;
    } finally {
      if (context != null) {
        context.close();
      }
    }
  }

  /** Nothing but the environment: no component scan, no auto-configuration. */
  @Configuration(proxyBeanMethods = false)
  static class Settings {}
}
