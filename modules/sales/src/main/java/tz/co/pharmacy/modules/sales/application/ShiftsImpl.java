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
    Page<Shift> page =
        Keyset.page(
            shifts, Specs.equal("branchUid", ExecutionContext.requireBranch()), query, s -> s);
    Instant now = clock.instant();
    Map<java.util.UUID, Figures> figures =
        figures(page.items(), s -> s.getClosedAt() == null ? now : s.getClosedAt());
    return page.map(s -> view(s, figures.get(s.getUid())));
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
    return figures(java.util.List.of(shift), s -> until).get(shift.getUid());
  }

  /**
   * The figures of several shifts in four statements whatever their number (adversarial review
   * PERF-16: the list ran four per shift), each shift a row of a {@code VALUES} window: its branch,
   * cashier, opening and {@code until}.
   */
  private Map<java.util.UUID, Figures> figures(
      java.util.List<Shift> shifts, java.util.function.Function<Shift, Instant> until) {
    Map<java.util.UUID, Figures> figures = new java.util.HashMap<>();
    if (shifts.isEmpty()) {
      return figures;
    }
    StringBuilder windows =
        new StringBuilder("with w (uid, branch_uid, user_uid, opened, until) as (values ");
    java.util.List<Object> parameters = new java.util.ArrayList<>();
    for (int i = 0; i < shifts.size(); i++) {
      Shift shift = shifts.get(i);
      windows.append(i == 0 ? "" : ", ").append("(?::uuid, ?::uuid, ?::uuid, ?::timestamptz,");
      windows.append(" ?::timestamptz)");
      parameters.add(shift.getUid());
      parameters.add(shift.getBranchUid());
      parameters.add(shift.getCreatedByUid());
      parameters.add(shift.getOpenedAt().atOffset(ZoneOffset.UTC));
      parameters.add(until.apply(shift).atOffset(ZoneOffset.UTC));
    }
    windows.append(") ");
    String w = windows.toString();
    Object[] window = parameters.toArray();
    Map<java.util.UUID, Long> count = new java.util.HashMap<>();
    Map<java.util.UUID, BigDecimal[]> totals = new java.util.HashMap<>();
    String inWindow =
        " s.branch_uid = w.branch_uid and s.created_by_uid = w.user_uid"
            + " and s.created_at >= w.opened and s.created_at <= w.until";
    jdbc.query(
        w
            + "select w.uid, count(s.id) as sales, coalesce(sum(s.total), 0) as total,"
            + " coalesce(sum(s.change_due), 0) as change from w left join sales.sales s on"
            + inWindow
            + " group by w.uid",
        r -> {
          java.util.UUID uid = r.getObject("uid", java.util.UUID.class);
          count.put(uid, r.getLong("sales"));
          totals.put(
              uid, new BigDecimal[] {r.getBigDecimal("total"), r.getBigDecimal("change"), null});
        },
        window);
    Map<java.util.UUID, Map<String, BigDecimal>> tenders = new java.util.HashMap<>();
    jdbc.query(
        w
            + "select w.uid, p.method, coalesce(sum(coalesce(p.base_amount, p.amount)), 0) as"
            + " amount from w join sales.sales s on"
            + inWindow
            + " join sales.sale_payments p on s.id = p.sale_id group by w.uid, p.method",
        r -> {
          tenders
              .computeIfAbsent(r.getObject("uid", java.util.UUID.class), k -> new TreeMap<>())
              .put(r.getString("method"), Amounts.money(r.getBigDecimal("amount")));
        },
        window);
    // Cash refunds paid from this drawer. A refund counts against the open shift of whoever paid
    // it; when that person has no open shift here (a supervisor refunding from a cashier's drawer),
    // against the shift of the cashier who made the original sale, if open; otherwise against the
    // branch's only open shift.
    String openShift =
        " from sales.shifts o where o.branch_uid = r.branch_uid and o.opened_at <= r.created_at"
            + " and (o.closed_at is null or o.closed_at >= r.created_at)";
    Map<java.util.UUID, BigDecimal> refunds = new java.util.HashMap<>();
    jdbc.query(
        w
            + "select w.uid, coalesce(sum("
            + ReturnSql.cashPaidOut("r")
            + "), 0) as cash"
            + " from w join sales.sale_returns r on r.branch_uid = w.branch_uid"
            + " and r.created_at >= w.opened and r.created_at <= w.until"
            + " and (r.created_by_uid = w.user_uid"
            + " or (not exists (select 1"
            + openShift
            + " and o.created_by_uid = r.created_by_uid)"
            + " and (exists (select 1 from sales.sales s where s.uid = r.sale_uid"
            + " and s.created_by_uid = w.user_uid)"
            + " or (not exists (select 1 from sales.sales s where s.uid = r.sale_uid"
            + " and exists (select 1"
            + openShift
            + " and o.created_by_uid = s.created_by_uid))"
            + " and (select count(*)"
            + openShift
            + ") = 1))))"
            + " group by w.uid",
        r -> {
          refunds.put(r.getObject("uid", java.util.UUID.class), r.getBigDecimal("cash"));
        },
        window);
    // Vouchers this cashier sold for cash at this branch: the money is in the drawer.
    Map<java.util.UUID, BigDecimal> vouchers = new java.util.HashMap<>();
    jdbc.query(
        w
            + "select w.uid, coalesce(sum(v.initial_value), 0) as cash from w join sales.vouchers v"
            + " on v.branch_uid = w.branch_uid and v.created_by_uid = w.user_uid"
            + " and v.paid_by = 'CASH' and v.created_at >= w.opened and v.created_at <= w.until"
            + " group by w.uid",
        r -> {
          vouchers.put(r.getObject("uid", java.util.UUID.class), r.getBigDecimal("cash"));
        },
        window);
    for (Shift shift : shifts) {
      java.util.UUID uid = shift.getUid();
      BigDecimal[] sums = totals.get(uid);
      figures.put(
          uid,
          new Figures(
              count.getOrDefault(uid, 0L),
              Amounts.money(sums == null ? BigDecimal.ZERO : sums[0]),
              Amounts.money(sums == null ? BigDecimal.ZERO : sums[1]),
              tenders.getOrDefault(uid, new TreeMap<>()),
              Amounts.money(refunds.getOrDefault(uid, BigDecimal.ZERO)),
              Amounts.money(vouchers.getOrDefault(uid, BigDecimal.ZERO))));
    }
    return figures;
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
