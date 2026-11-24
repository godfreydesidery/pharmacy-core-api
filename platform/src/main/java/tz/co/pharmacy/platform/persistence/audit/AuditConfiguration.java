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
  AuditPartitions auditPartitions(
      DataSource dataSource,
      Clock clock,
      SingleRunner runner,
      ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters,
      @org.springframework.beans.factory.annotation.Value(
              "${pharmacy.audit.repair-default-partition:false}")
          boolean repair) {
    return new AuditPartitions(
        new JdbcTemplate(dataSource),
        clock,
        runner,
        meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new),
        repair);
  }

  /**
   * Keeps the partitions of the clock's month and the next two in place: at start, every night, and
   * whenever a simulated clock moves forward. The start and nightly runs go through {@link
   * SingleRunner}, so two instances never create the same partition at once.
   *
   * <p>OPS-18: each run also counts what the default partition holds (it should hold nothing; an
   * entry there blocks its month's partition for good). The count is the gauge {@code
   * pharmacy.audit.default_partition.rows}; above zero the run logs {@value #TRAPPED} and is marked
   * failed, and with {@code pharmacy.audit.repair-default-partition} on it moves the rows into
   * their monthly partitions ({@code audit.repair_default_partition()}, also the runbook step).
   */
  public static class AuditPartitions {

    private static final String JOB = "audit-partitions";

    /** The start of the line logged while the default partition holds entries. */
    public static final String TRAPPED = "AUDIT_DEFAULT_PARTITION_ROWS";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SingleRunner runner;
    private final boolean repair;
    private final java.util.concurrent.atomic.AtomicLong trapped =
        new java.util.concurrent.atomic.AtomicLong();

    AuditPartitions(
        JdbcTemplate jdbc,
        Clock clock,
        SingleRunner runner,
        io.micrometer.core.instrument.MeterRegistry meters,
        boolean repair) {
      this.jdbc = jdbc;
      this.clock = clock;
      this.runner = runner;
      this.repair = repair;
      io.micrometer.core.instrument.Gauge.builder(
              "pharmacy.audit.default_partition.rows",
              trapped,
              java.util.concurrent.atomic.AtomicLong::get)
          .description("Audit entries in the default partition (should be 0)")
          .register(meters);
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
            long rows = watchDefault();
            if (rows > 0) {
              run.failed(
                  null,
                  TRAPPED
                      + " "
                      + rows
                      + (repair
                          ? " (moved into their months)"
                          : " (run select audit.repair_default_partition())"));
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

    /**
     * Counts the default partition's entries (the gauge), warns when there are any, and moves them
     * into their months when the repair is on. Returns how many it held; -1 when it could not tell.
     */
    public long watchDefault() {
      long rows;
      try {
        Long counted = jdbc.queryForObject("select audit.default_partition_rows()", Long.class);
        rows = counted == null ? 0 : counted;
      } catch (RuntimeException failure) {
        log.warn("Could not count the audit default partition: {}", failure.getMessage());
        return -1;
      }
      trapped.set(rows);
      if (rows == 0) {
        return 0;
      }
      log.warn(
          "{} the audit default partition holds {} entries; their months' partitions cannot be"
              + " created until they are moved (runbook: select audit.repair_default_partition())",
          TRAPPED,
          rows);
      if (repair) {
        repair();
      }
      return rows;
    }

    /** Moves the default partition's entries into their months; returns how many moved. */
    public long repair() {
      Long moved = jdbc.queryForObject("select audit.repair_default_partition()", Long.class);
      long count = moved == null ? 0 : moved;
      log.info("Moved {} audit entries out of the default partition into their months", count);
      Long left = jdbc.queryForObject("select audit.default_partition_rows()", Long.class);
      trapped.set(left == null ? 0 : left);
      return count;
    }
  }
}
