package tz.co.pharmacy.platform.persistence.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The record of scheduled job runs ({@code platform.job_runs}): written by {@link
 * tz.co.pharmacy.platform.persistence.SingleRunner} on whichever instance ran (or skipped) a job,
 * read by root's {@code GET /api/v1/platform/jobs}, and trimmed by the nightly retention. Times are
 * the database's, the wall time, so they line up with the logs whatever the application clock.
 */
@Component
public class JobRuns {

  public enum Outcome {
    OK,
    FAILED,
    SKIPPED
  }

  /** One run as recorded; {@code outcome} is null while it runs (or if its instance died). */
  public record RunView(
      long id,
      String job,
      String instance,
      Instant startedAt,
      Instant finishedAt,
      Outcome outcome,
      String message,
      List<JobRun.Failure> failures) {}

  /**
   * A job's state: its latest run that was not skipped (it may still be running), when it last
   * succeeded, its latest failure with what failed, when an instance last skipped it (another held
   * the job), and how many runs failed in the last 24 hours.
   */
  public record JobStatus(
      String job,
      RunView lastRun,
      Instant lastSucceededAt,
      RunView lastFailure,
      Instant lastSkippedAt,
      long failedLast24Hours) {}

  private static final String COLUMNS =
      "id, job, instance, started_at, finished_at, outcome, message, failures::text as failures";

  private final JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  public JobRuns(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /** A run starts; returns its id. */
  public long started(String job, String instance) {
    Long id =
        jdbc.queryForObject(
            "insert into platform.job_runs (job, instance) values (?, ?) returning id",
            Long.class,
            job,
            instance);
    return id == null ? 0 : id;
  }

  public void finished(long id, Outcome outcome, String message, List<JobRun.Failure> failures) {
    jdbc.update(
        "update platform.job_runs set finished_at = now(), outcome = ?, message = ?,"
            + " failures = ?::jsonb where id = ?",
        outcome.name(),
        message,
        json.writeValueAsString(failures == null ? List.of() : failures),
        id);
  }

  /** An instance did not run the job: another instance holds it. */
  public void skipped(String job, String instance, String why) {
    jdbc.update(
        "insert into platform.job_runs (job, instance, finished_at, outcome, message)"
            + " values (?, ?, now(), 'SKIPPED', ?)",
        job,
        instance,
        why);
  }

  /** Every job that has run, by name, with its latest state. */
  public List<JobStatus> statuses() {
    return jdbc.query(
        "select j.job,"
            + " r.id, r.instance, r.started_at, r.finished_at, r.outcome, r.message,"
            + " r.failures::text as failures,"
            + " f.id as f_id, f.instance as f_instance, f.started_at as f_started_at,"
            + " f.finished_at as f_finished_at, f.message as f_message,"
            + " f.failures::text as f_failures,"
            + " (select max(o.started_at) from platform.job_runs o"
            + "   where o.job = j.job and o.outcome = 'OK') as ok_at,"
            + " (select max(s.started_at) from platform.job_runs s"
            + "   where s.job = j.job and s.outcome = 'SKIPPED') as skipped_at,"
            + " (select count(*) from platform.job_runs c where c.job = j.job"
            + "   and c.outcome = 'FAILED' and c.started_at > now() - interval '24 hours')"
            + "   as failed_24h"
            + " from (select distinct job from platform.job_runs) j"
            + " left join lateral (select * from platform.job_runs x where x.job = j.job"
            + "   and x.outcome is distinct from 'SKIPPED' order by x.started_at desc, x.id desc"
            + "   limit 1) r on true"
            + " left join lateral (select * from platform.job_runs y where y.job = j.job"
            + "   and y.outcome = 'FAILED' order by y.started_at desc, y.id desc limit 1) f on true"
            + " order by j.job",
        (rs, n) -> {
          String job = rs.getString("job");
          RunView last =
              rs.getObject("id") == null
                  ? null
                  : new RunView(
                      rs.getLong("id"),
                      job,
                      rs.getString("instance"),
                      instant(rs, "started_at"),
                      instant(rs, "finished_at"),
                      outcome(rs.getString("outcome")),
                      rs.getString("message"),
                      failures(rs.getString("failures")));
          RunView failure =
              rs.getObject("f_id") == null
                  ? null
                  : new RunView(
                      rs.getLong("f_id"),
                      job,
                      rs.getString("f_instance"),
                      instant(rs, "f_started_at"),
                      instant(rs, "f_finished_at"),
                      Outcome.FAILED,
                      rs.getString("f_message"),
                      failures(rs.getString("f_failures")));
          return new JobStatus(
              job,
              last,
              instant(rs, "ok_at"),
              failure,
              instant(rs, "skipped_at"),
              rs.getLong("failed_24h"));
        });
  }

  /** The latest runs of one job, newest first (skipped ones too). */
  public List<RunView> recent(String job, int limit) {
    return jdbc.query(
        "select "
            + COLUMNS
            + " from platform.job_runs where job = ? order by started_at desc, id desc limit ?",
        (rs, n) -> view(rs),
        job,
        Math.max(1, Math.min(limit, 500)));
  }

  /** Deletes the runs older than {@code keep}; returns how many. */
  public int trim(Duration keep) {
    return jdbc.update(
        "delete from platform.job_runs where started_at < now() - ?::interval",
        keep.toSeconds() + " seconds");
  }

  private RunView view(ResultSet rs) throws SQLException {
    return new RunView(
        rs.getLong("id"),
        rs.getString("job"),
        rs.getString("instance"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        outcome(rs.getString("outcome")),
        rs.getString("message"),
        failures(rs.getString("failures")));
  }

  private List<JobRun.Failure> failures(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<Map<String, String>> raw =
        json.readValue(text, new TypeReference<List<Map<String, String>>>() {});
    List<JobRun.Failure> out = new ArrayList<>(raw.size());
    for (Map<String, String> item : raw) {
      String tenant = item.get("tenantUid");
      out.add(
          new JobRun.Failure(
              tenant == null || tenant.isBlank() ? null : UUID.fromString(tenant),
              item.get("error")));
    }
    return out;
  }

  private static Outcome outcome(String text) {
    return text == null ? null : Outcome.valueOf(text);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp at = rs.getTimestamp(column);
    return at == null ? null : at.toInstant();
  }
}
