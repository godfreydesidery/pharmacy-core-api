package tz.co.pharmacy.delivery.api.v1.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierAccounts;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierActivity;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.StatementKey;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * What the pharmacy owes each supplier, in the base currency: opening balance plus goods received,
 * less payments and returns; and one supplier's statement with a running balance.
 */
@RestController
@RequestMapping("/api/v1/suppliers")
@RequiredArgsConstructor
public class SupplierAccountsHandler {

  private final SupplierActivity activity;
  private final SupplierAccounts accounts;
  private final SupplierService suppliers;
  private final TenantCalendar calendar;

  /**
   * One supplier's position; {@code balance} is what is owed to them (negative: in credit). {@code
   * returned} counts returns the supplier credited; {@code awaitingCredit} is stock sent back whose
   * credit note has not come yet (FRD-16), not yet off the balance.
   */
  public record SupplierBalance(
      UUID supplierUid,
      String supplierName,
      BigDecimal opening,
      BigDecimal received,
      BigDecimal returned,
      BigDecimal paid,
      BigDecimal balance,
      LocalDate lastActivity,
      BigDecimal awaitingCredit) {}

  /**
   * A statement line: OPENING, RECEIPT, PAYMENT or RETURN. {@code amount} raises what is owed when
   * positive (opening, receipt) and lowers it when negative (payment, return).
   */
  public record StatementLine(
      LocalDate date,
      String type,
      UUID documentUid,
      String number,
      String reference,
      BigDecimal amount,
      BigDecimal balance) {}

  /**
   * A supplier's statement for [from, to]: {@code opening} is what was owed before {@code from},
   * each line carries the running balance, {@code balance} is what is owed at the end of {@code
   * to}, and {@code nextCursor} (null on the last page) reads the following lines.
   */
  public record SupplierStatement(
      UUID supplierUid,
      String supplierName,
      LocalDate from,
      LocalDate to,
      BigDecimal opening,
      List<StatementLine> lines,
      BigDecimal balance,
      String nextCursor) {}

  @GetMapping("/balances")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<?> balances() {
    ZoneId zone = calendar.zone();
    Map<UUID, SupplierActivity.Totals> purchasing = new LinkedHashMap<>();
    activity.totals().forEach(t -> purchasing.put(t.supplierUid(), t));
    Map<UUID, SupplierAccounts.Totals> finance = new LinkedHashMap<>();
    accounts.totals().forEach(t -> finance.put(t.supplierUid(), t));
    java.util.Set<UUID> suppliers = new java.util.LinkedHashSet<>(purchasing.keySet());
    suppliers.addAll(finance.keySet());
    List<SupplierBalance> rows = new ArrayList<>();
    for (UUID supplier : suppliers) {
      SupplierActivity.Totals p = purchasing.get(supplier);
      SupplierAccounts.Totals f = finance.get(supplier);
      BigDecimal opening = f == null ? Amounts.ZERO : f.opening();
      BigDecimal paid = f == null ? Amounts.ZERO : f.paid();
      BigDecimal received = p == null ? Amounts.ZERO : p.received();
      BigDecimal returned = p == null ? Amounts.ZERO : p.returned();
      LocalDate lastOfPurchasing = p == null ? null : LocalDate.ofInstant(p.last(), zone);
      LocalDate lastOfFinance = f == null ? null : f.last();
      LocalDate last =
          lastOfPurchasing == null
              ? lastOfFinance
              : lastOfFinance == null || lastOfPurchasing.isAfter(lastOfFinance)
                  ? lastOfPurchasing
                  : lastOfFinance;
      rows.add(
          new SupplierBalance(
              supplier,
              p == null ? f.supplierName() : p.supplierName(),
              opening,
              received,
              returned,
              paid,
              Amounts.money(opening.add(received).subtract(returned).subtract(paid)),
              last,
              p == null ? Amounts.ZERO : p.awaitingCredit()));
    }
    return ApiResponse.ok(
        Pages.slice(
            rows.stream()
                .sorted(
                    Comparator.comparing(SupplierBalance::balance)
                        .reversed()
                        .thenComparing(SupplierBalance::supplierName))
                .toList()),
        "Supplier balances retrieved");
  }

  /** The longest period one statement covers, in days (a year, leap day included). */
  static final int MAX_DAYS = 366;

  /** The period a statement covers when none is named: the last 90 days. */
  static final int DEFAULT_DAYS = 90;

  /**
   * One supplier's statement for the days [from, to] (the pharmacy's local days; by default the 90
   * days to today, at most {@link #MAX_DAYS}): what was owed before {@code from} ({@code opening}),
   * the lines in order with a running balance, read a page at a time ({@code cursor}, {@code limit}
   * up to 100, by default 100) after the last line of the previous page, and what is owed at the
   * end of {@code to} ({@code balance}). Before PERF-5 it loaded the supplier's whole history in
   * one answer.
   */
  @GetMapping("/{uid}/statement")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<SupplierStatement> statement(
      @PathVariable UUID uid,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    SupplierView supplier =
        suppliers
            .find(uid)
            .orElseThrow(() -> Refusal.notFound("supplier_not_found", "No such supplier."));
    LocalDate end = to == null ? calendar.today() : to;
    LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
    if (start.isAfter(end)) {
      throw Refusal.invalid("invalid_period", "from must not be after to.", "from");
    }
    if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
      throw Refusal.invalid(
              "period_too_long",
              "A statement covers at most " + MAX_DAYS + " days; choose a shorter period.",
              "from")
          .with("maxDays", MAX_DAYS);
    }
    int size = limit == null ? PageQuery.MAX_LIMIT : PageQuery.of(null, limit).limit();
    StatementKey after = StatementKey.ofCursor(cursor);
    ZoneId zone = calendar.zone();

    BigDecimal opening =
        Amounts.money(accounts.sum(uid, start, null).add(activity.sum(uid, start, null, zone)));
    BigDecimal closing =
        Amounts.money(
            accounts
                .sum(uid, end.plusDays(1), null)
                .add(activity.sum(uid, end.plusDays(1), null, zone)));
    BigDecimal balance =
        after == null
            ? opening
            : Amounts.money(
                opening
                    .add(accounts.sum(uid, start, after))
                    .add(activity.sum(uid, start, after, zone)));

    record Raw(StatementKey key, String type, String ref, BigDecimal amount) {}
    List<Raw> ours = new ArrayList<>();
    for (SupplierAccounts.Entry e : accounts.entries(uid, start, end, after, size + 1)) {
      ours.add(new Raw(e.key(), e.type(), e.reference(), e.amount()));
    }
    List<Raw> theirs = new ArrayList<>();
    for (SupplierActivity.Entry e : activity.entries(uid, start, end, zone, after, size + 1)) {
      theirs.add(new Raw(e.key(), e.type(), e.reference(), e.amount()));
    }
    // Each list is in statement order already; the two never share a day and kind (finance has
    // openings and payments, purchasing receipts and returns), so they merge on those alone.
    Comparator<Raw> order =
        Comparator.comparing((Raw r) -> r.key().date()).thenComparingInt(r -> r.key().order());
    List<StatementLine> lines = new ArrayList<>();
    StatementKey last = null;
    int i = 0;
    int j = 0;
    while (lines.size() < size && (i < ours.size() || j < theirs.size())) {
      Raw next =
          j >= theirs.size() || (i < ours.size() && order.compare(ours.get(i), theirs.get(j)) <= 0)
              ? ours.get(i++)
              : theirs.get(j++);
      last = next.key();
      balance = Amounts.money(balance.add(next.amount()));
      lines.add(
          new StatementLine(
              next.key().date(),
              next.type(),
              next.key().uid(),
              next.key().number(),
              next.ref(),
              next.amount(),
              balance));
    }
    boolean more = i < ours.size() || j < theirs.size();
    String nextCursor = more && last != null ? last.cursor() : null;
    return ApiResponse.ok(
        new SupplierStatement(
            supplier.uid(), supplier.name(), start, end, opening, lines, closing, nextCursor),
        "Supplier statement retrieved");
  }
}
