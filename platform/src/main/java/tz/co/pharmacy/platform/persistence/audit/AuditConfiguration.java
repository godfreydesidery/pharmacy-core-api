package tz.co.pharmacy.platform.persistence.audit;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tz.co.pharmacy.platform.persistence.SingleRunner;

/**
 * The audit trail: the {@link AuditLog} bean, the Hibernate listeners that record every change, and
 * the monthly partitions, created two months ahead at start and every night.
 */
@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AuditConfiguration.class);

  @Bean
  AuditLog auditLog(DataSource dataSource, ObjectProvider<AuditLabels> labels) {
    return new JdbcAuditLog(new JdbcTemplate(dataSource), labels);
  }

  /**
   * {@code pharmacy.audit.ignored-entities}: entity classes, by fully qualified name, left out of
   * the automatic trail besides those marked {@link AuditIgnore} (PERF-7). A name that is not a
   * class stops the start, so a renamed entity cannot quietly come back into the trail.
   */
  @Bean
  EntityAuditListener entityAuditListener(
      DataSource dataSource,
      EntityManagerFactory entityManagerFactory,
      org.springframework.core.env.Environment environment) {
    java.util.Set<Class<?>> ignored = new java.util.HashSet<>();
    for (String name :
        org.springframework.boot.context.properties.bind.Binder.get(environment)
            .bind(
                "pharmacy.audit.ignored-entities",
                org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
            .orElse(java.util.List.of())) {
      if (name.isBlank()) {
        continue;
      }
      try {
        ignored.add(Class.forName(name.trim(), false, AuditConfiguration.class.getClassLoader()));
      } catch (ClassNotFoundException unknown) {
        throw new IllegalStateException(
            "pharmacy.audit.ignored-entities names " + name + ", which is no class", unknown);
      }
    }
    EntityAuditListener listener =
        new EntityAuditListener(new AuditWriter(new JdbcTemplate(dataSource)), ignored);
    EventListenerRegistry registry =
        entityManagerFactory
            .unwrap(SessionFactoryImplementor.class)
            .getServiceRegistry()
            .requireService(EventListenerRegistry.class);
    registry.appendListeners(EventType.POST_INSERT, listener);
    registry.appendListeners(EventType.POST_UPDATE, listener);
    registry.appendListeners(EventType.POST_DELETE, listener);
    // After Hibernate's own flush: the entries the flush made are written in one batch.
    registry.appendListeners(EventType.FLUSH, listener);
    log.info("Audit trail: {} ledger entity classes left out by configuration", ignored.size());
    return listener;
  }

  @Bean
  AuditPartitions auditPartitions(DataSource dataSource, Clock clock, SingleRunner runner) {
    return new AuditPartitions(new JdbcTemplate(dataSource), clock, runner);
  }

  /**
   * Keeps the partitions of the clock's month and the next two in place: at start, every night, and
   * whenever a simulated clock moves forward. The start and nightly runs go through {@link
   * SingleRunner}, so two instances never create the same partition at once.
   */
  public static class AuditPartitions {

    private static final String JOB = "audit-partitions";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SingleRunner runner;

    AuditPartitions(JdbcTemplate jdbc, Clock clock, SingleRunner runner) {
      this.jdbc = jdbc;
      this.clock = clock;
      this.runner = runner;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 15 2 * * *", zone = "UTC")
    public void nightly() {
      runner.runAlone(
          JOB,
          run -> {
            if (!ensure()) {
              run.failed(null, "could not create the audit partitions (see the log)");
            }
          });
    }

    /** Creates the partitions now; returns whether that worked. */
    public boolean ensure() {
      try {
        jdbc.queryForList(
            "select audit.ensure_partitions(?::date, 2)", Date.valueOf(LocalDate.now(clock)));
        return true;
      } catch (RuntimeException failure) {
        log.warn("Could not create the audit partitions: {}", failure.getMessage());
        return false;
      }
    }
  }
}
