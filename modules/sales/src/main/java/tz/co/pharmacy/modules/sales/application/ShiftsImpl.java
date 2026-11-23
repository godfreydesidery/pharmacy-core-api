package tz.co.pharmacy.modules.sales.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.sales.domain.Shift;
import tz.co.pharmacy.modules.sales.persistence.ShiftRepository;
import tz.co.pharmacy.modules.sales.ports.inbound.Shifts;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesSettings;
import tz.co.pharmacy.platform.approval.ApprovalActions;
import tz.co.pharmacy.platform.approval.Approvals;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

/**
 * Till shifts. The figures are the cashier's own sales and returns at the branch while the shift
 * was open, read with plain SQL; row-level security keeps them to the tenant.
 */
@Service
class ShiftsImpl implements Shifts {

  private final ShiftRepository shifts;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final SalesSettings settings;
  private final Approvals approvals;

  ShiftsImpl(
      ShiftRepository shifts,
      DataSource dataSource,
      Clock clock,
      SalesSettings settings,
      Approvals approvals) {
    this.shifts = shifts;
    this.jdbc = new JdbcTemplate(dataSource);
    this.clock = clock;
    this.settings = settings;
    this.approvals = approvals;
  }

  /**
   * FRD-7: when the pharmacy requires it, selling, refunding and issuing a voucher need the
   * caller's own open shift at this branch (409 shift_required), so every shilling taken or paid
   * out is in a cashier's drawer. The shift is share-locked for the caller's transaction: it cannot
   * close until the sale or refund has committed, and its close then counts it. A platform service
   * (the demo seed, a job) is no cashier and has no drawer: the rule is for people.
   */
  void requireOpenShift() {
    if (ExecutionContext.actor().type() != tz.co.pharmacy.platform.kernel.Actor.ActorType.USER
        || !settings.requireOpenShift()) {
      return;
    }
    if (shifts
        .shareOpen(ExecutionContext.requireBranch(), ExecutionContext.actor().uid())
        .isEmpty()) {
      throw Refusal.conflict(
          "shift_required", "Open your till shift at this branch before selling or refunding.");
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ShiftView> current() {
    return open().map(s -> view(s, figures(s, clock.instant())));
  }

  @Override
  @Transactional
  public ShiftView open(BigDecimal openingFloat) {
    BigDecimal amount = openingFloat == null ? BigDecimal.ZERO : openingFloat;
    if (amount.signum() < 0) {
      throw Refusal.invalid("invalid_amount", "The float cannot be negative.", "openingFloat");
    }
    if (open().isPresent()) {
      throw Refusal.conflict("shift_open", "You already have an open shift at this branch.");
    }
    Shift shift =
        shifts.save(
            new Shift(
                ExecutionContext.requireBranch(),
                ExecutionContext.actor().reference(),
                ExecutionContext.actor().name(),
                amount,
                clock.instant()));
    return view(shift, figures(shift, clock.instant()));
  }

  @Override
  @Transactional
  public ShiftView close(BigDecimal countedCash, String note) {
    BigDecimal counted = Checks.required(countedCash, "countedCash");
    if (counted.signum() < 0) {
      throw Refusal.invalid(
          "invalid_amount", "The counted cash cannot be negative.", "countedCash");
    }
    Shift shift =
        shifts
            .lockOpen(ExecutionContext.requireBranch(), ExecutionContext.actor().uid())
            .orElseThrow(
                () -> Refusal.invalid("no_open_shift", "You have no open shift at this branch."));
    Instant now = clock.instant();
    Figures f = figures(shift, now);
    // Cash short or over by more than the pharmacy's approval limit: a manager approves the close.
    BigDecimal variance = Amounts.money(counted.subtract(f.expected(shift)));
    approvals.require(
        ApprovalActions.SHIFT_VARIANCE,
        variance.abs(),
        "Shift " + shift.getUid() + " (" + shift.getCashier() + ")");
    shift.close(f.expected(shift), counted, Checks.optionalText(note, "note", 500), now);
    return view(shift, f);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ShiftView> list(PageQuery query) {
    return Keyset.page(
        shifts,
        Specs.equal("branchUid", ExecutionContext.requireBranch()),
        query,
        s -> view(s, figures(s, s.getClosedAt() == null ? clock.instant() : s.getClosedAt())));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<LastCount> lastCount() {
    return shifts
        .findFirstByBranchUidAndStatusOrderByClosedAtDesc(
            ExecutionContext.requireBranch(), "CLOSED")
        .filter(s -> s.getCountedCash() != null)
        .map(s -> new LastCount(s.getCountedCash(), s.getClosedAt(), s.getCashierName()));
  }

  private Optional<Shift> open() {
    return shifts.findByBranchUidAndCreatedByUidAndStatus(
        ExecutionContext.requireBranch(), ExecutionContext.actor().uid(), "OPEN");
  }

  private record Figures(
      long sales,
      BigDecimal total,
      BigDecimal change,
      Map<String, BigDecimal> tenders,
      BigDecimal cashRefunds,
      BigDecimal voucherCash) {

    BigDecimal expected(Shift shift) {
      return Amounts.money(
          shift
              .getOpeningFloat()
              .add(tenders.getOrDefault("CASH", BigDecimal.ZERO))
              .add(voucherCash)
              .subtract(change)
              .subtract(cashRefunds));
    }
  }

  private Figures figures(Shift shift, Instant until) {
    Object[] window = {
      shift.getBranchUid(),
      shift.getCreatedByUid(),
      shift.getOpenedAt().atOffset(ZoneOffset.UTC),
      until.atOffset(ZoneOffset.UTC)
    };
    long[] count = {0};
    BigDecimal[] totals = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    String where =
        " where s.branch_uid = ? and s.created_by_uid = ? and s.created_at >= ?"
            + " and s.created_at <= ?";
    jdbc.query(
        "select count(*) as sales, coalesce(sum(s.total), 0) as total,"
            + " coalesce(sum(s.change_due), 0) as change from sales.sales s"
            + where,
        r -> {
          count[0] = r.getLong("sales");
          totals[0] = r.getBigDecimal("total");
          totals[1] = r.getBigDecimal("change");
        },
        window);
    Map<String, BigDecimal> tenders = new TreeMap<>();
    jdbc.query(
        "select p.method, coalesce(sum(coalesce(p.base_amount, p.amount)), 0) as amount"
            + " from sales.sale_payments p join sales.sales s on s.id = p.sale_id"
            + where
            + " group by p.method",
        r -> {
          tenders.put(r.getString("method"), Amounts.money(r.getBigDecimal("amount")));
        },
        window);
    // Cash refunds paid from this drawer. A refund counts against the open shift of whoever paid
    // it; when that person has no open shift here (a supervisor refunding from a cashier's drawer),
    // against the shift of the cashier who made the original sale, if open; otherwise against the
    // branch's only open shift.
    String openShift =
        " from sales.shifts o where o.branch_uid = r.branch_uid and o.opened_at <= r.created_at"
            + " and (o.closed_at is null or o.closed_at >= r.created_at)";
    jdbc.query(
        "select coalesce(sum("
            + ReturnSql.cashPaidOut("r")
            + "), 0) as cash"
            + " from sales.sale_returns r"
            + " where r.branch_uid = ?"
            + " and r.created_at >= ? and r.created_at <= ?"
            + " and (r.created_by_uid = ?"
            + " or (not exists (select 1"
            + openShift
            + " and o.created_by_uid = r.created_by_uid)"
            + " and (exists (select 1 from sales.sales s where s.uid = r.sale_uid"
            + " and s.created_by_uid = ?)"
            + " or (not exists (select 1 from sales.sales s where s.uid = r.sale_uid"
            + " and exists (select 1"
            + openShift
            + " and o.created_by_uid = s.created_by_uid))"
            + " and (select count(*)"
            + openShift
            + ") = 1))))",
        r -> {
          totals[2] = r.getBigDecimal("cash");
        },
        window[0],
        window[2],
        window[3],
        window[1],
        window[1]);
    // Vouchers this cashier sold for cash at this branch: the money is in the drawer.
    BigDecimal voucherCash =
        jdbc.queryForObject(
            "select coalesce(sum(v.initial_value), 0) from sales.vouchers v"
                + " where v.branch_uid = ? and v.created_by_uid = ? and v.paid_by = 'CASH'"
                + " and v.created_at >= ? and v.created_at <= ?",
            BigDecimal.class,
            window);
    return new Figures(
        count[0],
        Amounts.money(totals[0]),
        Amounts.money(totals[1]),
        tenders,
        Amounts.money(totals[2]),
        Amounts.money(voucherCash));
  }

  private static ShiftView view(Shift s, Figures f) {
    return new ShiftView(
        s.getUid(),
        s.getCashier(),
        s.getCashierName(),
        s.getStatus(),
        s.getOpenedAt(),
        s.getClosedAt(),
        s.getOpeningFloat(),
        f.sales(),
        f.total(),
        f.tenders(),
        f.change(),
        f.cashRefunds(),
        f.voucherCash(),
        s.getExpectedCash() != null ? s.getExpectedCash() : f.expected(s),
        s.getCountedCash(),
        s.getVariance(),
        s.getNote());
  }
}
