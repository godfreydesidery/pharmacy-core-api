package tz.co.pharmacy.platform.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Migrates every module's schema as soon as the application's {@link DataSource} exists, and so
 * before Hibernate, which needs the data source, validates the schema. With {@code
 * pharmacy.database.migrations-enabled=false} a deployment migrates in a separate step and the API
 * holds no owner password.
 */
public class MigrationsBeforeJpa implements BeanPostProcessor, EnvironmentAware {

  private Environment environment;
  private boolean migrated;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof DataSource && !migrated) {
      migrated = true;
      if (!"false".equalsIgnoreCase(property("pharmacy.database.migrations-enabled", "true"))) {
        new ModuleMigrations(
                property("spring.datasource.url", null),
                property("pharmacy.database.owner-username", null),
                property("pharmacy.database.owner-password", ""),
                property("spring.datasource.username", null))
            .migrate();
      }
    }
    return bean;
  }

  private String property(String name, String fallback) {
    String value = environment.getProperty(name, fallback == null ? "" : fallback);
    if (value.isBlank() && fallback == null) {
      throw new IllegalStateException(name + " must be set to migrate the schemas");
    }
    return value;
  }
}
