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

  @Bean
  EntityAuditListener entityAuditListener(
      DataSource dataSource, EntityManagerFactory entityManagerFactory) {
    EntityAuditListener listener =
        new EntityAuditListener(new AuditWriter(new JdbcTemplate(dataSource)));
    EventListenerRegistry registry =
        entityManagerFactory
            .unwrap(SessionFactoryImplementor.class)
            .getServiceRegistry()
            .requireService(EventListenerRegistry.class);
    registry.appendListeners(EventType.POST_INSERT, listener);
    registry.appendListeners(EventType.POST_UPDATE, listener);
    registry.appendListeners(EventType.POST_DELETE, listener);
    return listener;
  }

  @Bean
  AuditPartitions auditPartitions(DataSource dataSource, Clock clock) {
    return new AuditPartitions(new JdbcTemplate(dataSource), clock);
  }

  /**
   * Keeps the partitions of the clock's month and the next two in place: at start, every night, and
   * whenever a simulated clock moves forward.
   */
  public static class AuditPartitions {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AuditPartitions(JdbcTemplate jdbc, Clock clock) {
      this.jdbc = jdbc;
      this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 15 2 * * *", zone = "UTC")
    public void ensure() {
      try {
        jdbc.queryForList(
            "select audit.ensure_partitions(?::date, 2)", Date.valueOf(LocalDate.now(clock)));
      } catch (RuntimeException failure) {
        log.warn("Could not create the audit partitions: {}", failure.getMessage());
      }
    }
  }
}
