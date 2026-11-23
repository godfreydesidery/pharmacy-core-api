package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

  @Bean
  static MigrationsBeforeJpa migrationsBeforeJpa() {
    return new MigrationsBeforeJpa();
  }

  /**
   * statement_timeout, lock_timeout and idle_in_transaction_session_timeout on every connection.
   */
  @Bean
  static DatabaseTimeouts databaseTimeouts() {
    return new DatabaseTimeouts();
  }

  @Bean
  PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
    return new TenantAwareTransactionManager(entityManagerFactory);
  }

  /**
   * Refuses to start when the application connects as a role that owns the schemas, is a superuser
   * or bypasses row-level security: tenant isolation would silently not apply.
   */
  @EventListener(ApplicationStartedEvent.class)
  void verifyApplicationRole(ApplicationStartedEvent event) {
    Environment environment = event.getApplicationContext().getEnvironment();
    if (environment.getProperty("pharmacy.database.allow-privileged-role", Boolean.class, false)) {
      return;
    }
    JdbcTemplate jdbc = new JdbcTemplate(event.getApplicationContext().getBean(DataSource.class));
    Map<String, Object> role =
        jdbc.queryForMap(
            "select rolsuper, rolbypassrls, current_user as name from pg_roles"
                + " where rolname = current_user");
    if (Boolean.TRUE.equals(role.get("rolsuper"))
        || Boolean.TRUE.equals(role.get("rolbypassrls"))) {
      throw new IllegalStateException(
          "The application role "
              + role.get("name")
              + " is a superuser or bypasses row-level security; tenant isolation would not apply.");
    }
    String owner = environment.getProperty("pharmacy.database.owner-username");
    if (owner != null && owner.equals(role.get("name"))) {
      throw new IllegalStateException(
          "The application must not connect as the schema owner " + owner + ".");
    }
  }
}
