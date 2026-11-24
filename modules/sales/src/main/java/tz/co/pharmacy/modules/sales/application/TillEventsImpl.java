package tz.co.pharmacy.modules.sales.application;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.sales.domain.Shift;
import tz.co.pharmacy.modules.sales.domain.TillEvent;
import tz.co.pharmacy.modules.sales.persistence.ShiftRepository;
import tz.co.pharmacy.modules.sales.persistence.TillEventRepository;
import tz.co.pharmacy.modules.sales.ports.inbound.TillEvents;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * The till's no-sale and abandoned-cart events (FRD-20). Each is one row, under the tenant's
 * row-level security, on the pharmacy's local day; the summary groups them in the database.
 */
@Service
class TillEventsImpl implements TillEvents {

  /** The longest period one summary covers, in days. */
  static final int MAX_DAYS = 92;

  private final TillEventRepository events;
  private final ShiftRepository shifts;
  private final TenantCalendar calendar;
  private final JdbcTemplate jdbc;

  TillEventsImpl(
      TillEventRepository events,
      ShiftRepository shifts,
      TenantCalendar calendar,
      DataSource dataSource) {
    this.events = events;
    this.shifts = shifts;
    this.calendar = calendar;
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  @Transactional
  public TillEventView record(RecordTillEvent command) {
    UUID branch = ExecutionContext.requireBranch();
    Checks.required(command, "body");
    String kind = Checks.text(command.kind(), "kind", 40).toUpperCase(Locale.ROOT);
    try {
      Kind.valueOf(kind);
    } catch (IllegalArgumentException unknown) {
      throw Refusal.invalid(
          "invalid_event_kind",
          "kind is CART_CANCELLED, CART_CLEARED, PARKED_DISCARDED or NO_SALE.",
          "kind");
    }
    int lines = command.lines() == null ? 0 : command.lines();
    if (lines < 0 || lines > 1000) {
      throw Refusal.invalid("invalid_lines", "lines must be between 0 and 1000.", "lines");
    }
    BigDecimal value = command.value() == null ? BigDecimal.ZERO : command.value();
    if (value.signum() < 0) {
      throw Refusal.invalid("invalid_amount", "value cannot be negative.", "value");
    }
    UUID cashier = ExecutionContext.actor().uid();
    UUID shift =
        cashier == null
            ? null
            : shifts
                .findByBranchUidAndCreatedByUidAndStatus(branch, cashier, "OPEN")
                .map(Shift::getUid)
                .orElse(null);
    TillEvent saved =
        events.save(
            new TillEvent(
                branch,
                shift,
                kind,
                lines,
                value,
                Checks.optionalText(command.reason(), "reason", 200),
                ExecutionContext.actor().name(),
                calendar.today()));
    return new TillEventView(
        saved.getUid(),
        saved.getKind(),
        saved.getLines(),
        saved.getValue(),
        saved.getOccurredOn(),
        saved.getShiftUid());
  }

  @Override
  @Transactional(readOnly = true)
  public List<TillEventSummary> summary(LocalDate from, LocalDate to) {
    LocalDate end = to == null ? calendar.today() : to;
    LocalDate start = from == null ? end.minusDays(6) : from;
    if (start.isAfter(end)) {
      throw Refusal.invalid("invalid_period", "from must not be after to.", "from");
    }
    if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
      throw Refusal.invalid(
              "period_too_long",
              "The report covers at most " + MAX_DAYS + " days; choose a shorter period.",
              "from")
          .with("maxDays", MAX_DAYS);
    }
    StringBuilder sql =
        new StringBuilder(
            "select occurred_on, branch_uid, created_by_uid, max(cashier_name) as cashier_name,"
                + " kind, count(*) as events, coalesce(sum(lines), 0) as lines,"
                + " coalesce(sum(value), 0) as value from sales.till_events"
                + " where occurred_on >= ? and occurred_on <= ?");
    List<Object> args = new ArrayList<>(List.of(Date.valueOf(start), Date.valueOf(end)));
    Set<UUID> branches = ExecutionContext.reportBranches();
    if (branches != null) {
      if (branches.isEmpty()) {
        return List.of();
      }
      sql.append(" and branch_uid = any(cast(? as uuid[]))");
      args.add("{" + String.join(",", branches.stream().map(UUID::toString).toList()) + "}");
    }
    sql.append(
        " group by occurred_on, branch_uid, created_by_uid, kind"
            + " order by occurred_on desc, cashier_name, kind");
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new TillEventSummary(
                rs.getObject("occurred_on", LocalDate.class),
                rs.getObject("branch_uid", UUID.class),
                rs.getObject("created_by_uid", UUID.class),
                rs.getString("cashier_name"),
                rs.getString("kind"),
                rs.getLong("events"),
                rs.getLong("lines"),
                Amounts.money(rs.getBigDecimal("value"))),
        args.toArray());
  }
}
