package tz.co.pharmacy.platform.composition;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Health "ledger" (adversarial review MON-13): DOWN while a journal posting (a ledger listener's
 * event publication) has been incomplete for longer than {@code pharmacy.events.stale-after} (30
 * minutes), so monitoring notices postings that keep failing; the details count every listener's
 * stale publications. It is part of {@code /actuator/health} only, never of the readiness or
 * liveness group: a stuck posting must not stop a deploy or restart the API. One indexed count.
 */
@Component("ledger")
class LedgerHealthIndicator implements HealthIndicator {

  private static final String LEDGER_LISTENERS =
      "(listener_id like '%LedgerReactions%' or listener_id like '%CreditReactions%')";

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration staleAfter;

  LedgerHealthIndicator(
      DataSource dataSource,
      Clock clock,
      @Value("${pharmacy.events.stale-after:PT30M}") Duration staleAfter) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.clock = clock;
    this.staleAfter = staleAfter;
  }

  @Override
  public Health health() {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select count(*) filter (where "
                + LEDGER_LISTENERS
                + ") as ledger, count(*) as all_listeners, min(publication_date) as oldest"
                + " from platform.event_publication"
                + " where completion_date is null and publication_date < ?",
            Timestamp.from(clock.instant().minus(staleAfter)));
    long ledger = ((Number) row.get("ledger")).longValue();
    long all = ((Number) row.get("all_listeners")).longValue();
    Health.Builder health = ledger > 0 ? Health.down() : Health.up();
    health
        .withDetail("stalePostings", ledger)
        .withDetail("staleEvents", all)
        .withDetail("staleAfter", staleAfter.toString());
    if (row.get("oldest") != null) {
      health.withDetail("oldest", row.get("oldest").toString());
    }
    return health.build();
  }
}
