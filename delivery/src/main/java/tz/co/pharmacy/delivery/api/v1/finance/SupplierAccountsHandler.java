package tz.co.pharmacy.delivery.api.v1.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierAccounts;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierActivity;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Refusal;
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

  /** One supplier's position; {@code balance} is what is owed to them (negative: in credit). */
  public record SupplierBalance(
      UUID supplierUid,
      String supplierName,
      BigDecimal opening,
      BigDecimal received,
      BigDecimal returned,
      BigDecimal paid,
      BigDecimal balance,
      LocalDate lastActivity) {}

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

  public record SupplierStatement(
      UUID supplierUid, String supplierName, List<StatementLine> lines, BigDecimal balance) {}

  @GetMapping("/balances")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<List<SupplierBalance>> balances() {
    ZoneId zone = calendar.zone();
    Map<UUID, SupplierBalance> rows = new LinkedHashMap<>();
    for (SupplierActivity.Totals t : activity.totals()) {
      rows.put(
          t.supplierUid(),
          new SupplierBalance(
              t.supplierUid(),
              t.supplierName(),
              Amounts.ZERO,
              t.received(),
              t.returned(),
              Amounts.ZERO,
              Amounts.ZERO,
              LocalDate.ofInstant(t.last(), zone)));
    }
    for (SupplierAccounts.Totals t : accounts.totals()) {
      SupplierBalance row = rows.get(t.supplierUid());
      LocalDate last =
          row == null || (t.last() != null && t.last().isAfter(row.lastActivity()))
              ? t.last()
              : row.lastActivity();
      rows.put(
          t.supplierUid(),
          new SupplierBalance(
              t.supplierUid(),
              row == null ? t.supplierName() : row.supplierName(),
              t.opening(),
              row == null ? Amounts.ZERO : row.received(),
              row == null ? Amounts.ZERO : row.returned(),
              t.paid(),
              Amounts.ZERO,
              last));
    }
    return ApiResponse.ok(
        rows.values().stream()
            .map(
                r ->
                    new SupplierBalance(
                        r.supplierUid(),
                        r.supplierName(),
                        r.opening(),
                        r.received(),
                        r.returned(),
                        r.paid(),
                        Amounts.money(
                            r.opening()
                                .add(r.received())
                                .subtract(r.returned())
                                .subtract(r.paid())),
                        r.lastActivity()))
            .sorted(
                Comparator.comparing(SupplierBalance::balance)
                    .reversed()
                    .thenComparing(SupplierBalance::supplierName))
            .toList(),
        "Supplier balances retrieved");
  }

  @GetMapping("/{uid}/statement")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<SupplierStatement> statement(@PathVariable UUID uid) {
    SupplierView supplier =
        suppliers
            .find(uid)
            .orElseThrow(() -> Refusal.notFound("supplier_not_found", "No such supplier."));
    ZoneId zone = calendar.zone();
    record Raw(
        LocalDate date, int order, String type, UUID uid, String no, String ref, BigDecimal a) {}
    List<Raw> raw = new ArrayList<>();
    for (SupplierAccounts.Entry e : accounts.entries(uid)) {
      raw.add(
          new Raw(
              e.on(),
              "OPENING".equals(e.type()) ? 0 : 2,
              e.type(),
              e.documentUid(),
              e.number(),
              e.reference(),
              e.amount()));
    }
    for (SupplierActivity.Entry e : activity.entries(uid)) {
      raw.add(
          new Raw(
              LocalDate.ofInstant(e.at(), zone),
              "RECEIPT".equals(e.type()) ? 1 : 3,
              e.type(),
              e.documentUid(),
              e.number(),
              e.reference(),
              e.amount()));
    }
    raw.sort(
        Comparator.comparing(Raw::date)
            .thenComparing(Raw::order)
            .thenComparing(r -> r.no() == null ? "" : r.no()));
    BigDecimal balance = Amounts.ZERO;
    List<StatementLine> lines = new ArrayList<>();
    for (Raw r : raw) {
      balance = Amounts.money(balance.add(r.a()));
      lines.add(new StatementLine(r.date(), r.type(), r.uid(), r.no(), r.ref(), r.a(), balance));
    }
    return ApiResponse.ok(
        new SupplierStatement(supplier.uid(), supplier.name(), lines, balance),
        "Supplier statement retrieved");
  }
}
