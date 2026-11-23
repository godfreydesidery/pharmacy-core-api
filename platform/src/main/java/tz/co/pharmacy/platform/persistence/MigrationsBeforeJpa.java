package tz.co.pharmacy.platform.persistence;

import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Deals with the migrations as soon as the application's {@link DataSource} exists, and so before
 * Hibernate, which needs the data source, validates the schema. In {@code migrate-and-run} mode it
 * migrates every module's schema as the owner role; in {@code run-only} mode it holds no owner
 * credentials and refuses to start while a migration is pending ({@link MigrationMode}). The {@code
 * migrate-only} mode never gets here: the launcher runs {@link MigrateOnly} instead.
 */
public class MigrationsBeforeJpa implements BeanPostProcessor, EnvironmentAware {

  private static final Logger LOG = LoggerFactory.getLogger(MigrationsBeforeJpa.class);

  private Environment environment;
  private boolean done;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof DataSource dataSource && !done) {
      done = true;
      switch (MigrationMode.of(environment)) {
        case MIGRATE_AND_RUN -> ModuleMigrations.from(environment).migrate();
        case RUN_ONLY -> requireMigrated(dataSource);
        case MIGRATE_ONLY ->
            throw new IllegalStateException(
                "migrate-only is chosen when the process is launched: set "
                    + MigrationMode.VARIABLE
                    + "=migrate-only, or pass --"
                    + MigrationMode.PROPERTY
                    + "=migrate-only.");
      }
    }
    return bean;
  }

  private void requireMigrated(DataSource dataSource) {
    List<String> pending =
        ModuleMigrations.pending(
            dataSource, environment.getProperty("spring.datasource.username", ""));
    if (!pending.isEmpty()) {
      throw new IllegalStateException(
          "run-only: the database is not at this release's schema version. Run the migrate-only"
              + " task first (PHARMACY_MIGRATIONS_MODE=migrate-only, with the owner secret).\n - "
              + String.join("\n - ", pending));
    }
    LOG.info("run-only: every schema is at this release's version; no owner credentials held");
  }
}
