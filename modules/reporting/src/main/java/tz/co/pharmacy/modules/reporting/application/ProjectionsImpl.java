package tz.co.pharmacy.modules.reporting.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.reporting.ports.inbound.ExpenseFact;
import tz.co.pharmacy.modules.reporting.ports.inbound.ProductFact;
import tz.co.pharmacy.modules.reporting.ports.inbound.Projections;
import tz.co.pharmacy.modules.reporting.ports.inbound.PurchaseFact;
import tz.co.pharmacy.modules.reporting.ports.inbound.SaleFact;
import tz.co.pharmacy.modules.reporting.ports.outbound.ReportingTenant;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * Adds each fact to the day it happened on, in the tenant's timezone. Each write is one atomic
 * upsert ({@code on conflict do update set x = t.x + excluded.x}), so concurrent listeners never
 * lose an update, and {@code reporting.processed_events} makes a redelivered event a no-op.
 */
@Service
class ProjectionsImpl implements Projections {

  private final JdbcTemplate jdbc;
  private final ReportingTenant tenant;

  ProjectionsImpl(DataSource dataSource, ReportingTenant tenant) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.tenant = tenant;
  }

  @Override
  @Transactional
  public void sale(SaleFact fact) {
    if (!firstDelivery(fact.eventId())) {
      return;
    }
    LocalDate day = LocalDate.ofInstant(fact.occurredAt(), tenant.zone());
    UUID branch = BranchFilter.orNone(fact.branchUid());
    int sign = fact.sign() < 0 ? -1 : 1;
    int invoice = sign > 0 ? 1 : 0;
    jdbc.update(
        "insert into reporting.daily_sales as t (tenant_uid, branch_uid, day, invoices, gross,"
            + " discount, tax, net, cost) values (platform.current_tenant_uid(), ?, ?, ?, ?, ?, ?,"
            + " ?, ?) on conflict (tenant_uid, branch_uid, day) do update set"
            + " invoices = t.invoices + excluded.invoices, gross = t.gross + excluded.gross,"
            + " discount = t.discount + excluded.discount, tax = t.tax + excluded.tax,"
            + " net = t.net + excluded.net, cost = t.cost + excluded.cost",
        branch,
        day,
        invoice,
        signed(fact.gross(), sign),
        signed(fact.discount(), sign),
        signed(fact.tax(), sign),
        signed(fact.net(), sign),
        signed(fact.cost(), sign));
    if (fact.products() != null) {
      for (ProductFact product : fact.products()) {
        jdbc.update(
            "insert into reporting.daily_product_sales as t (tenant_uid, branch_uid, day,"
                + " product_uid, product_name, quantity, net, cost) values"
                + " (platform.current_tenant_uid(), ?, ?, ?, ?, ?, ?, ?)"
                + " on conflict (tenant_uid, branch_uid, day, product_uid) do update set"
                + " product_name = excluded.product_name,"
                + " quantity = t.quantity + excluded.quantity, net = t.net + excluded.net,"
                + " cost = t.cost + excluded.cost",
            branch,
            day,
            product.productUid(),
            product.productName(),
            sign * product.quantity(),
            signed(product.net(), sign),
            signed(product.cost(), sign));
      }
    }
    if (fact.payments() != null) {
      fact.payments()
          .forEach(
              (method, amount) ->
                  jdbc.update(
                      "insert into reporting.daily_payments as t (tenant_uid, branch_uid, day,"
                          + " method, amount, payments) values (platform.current_tenant_uid(), ?,"
                          + " ?, ?, ?, ?) on conflict (tenant_uid, branch_uid, day, method) do"
                          + " update set amount = t.amount + excluded.amount,"
                          + " payments = t.payments + excluded.payments",
                      branch,
                      day,
                      method,
                      signed(amount, sign),
                      invoice));
    }
  }

  @Override
  @Transactional
  public void purchase(PurchaseFact fact) {
    if (!firstDelivery(fact.eventId())) {
      return;
    }
    jdbc.update(
        "insert into reporting.daily_purchases as t (tenant_uid, branch_uid, day, receipts, total)"
            + " values (platform.current_tenant_uid(), ?, ?, 1, ?)"
            + " on conflict (tenant_uid, branch_uid, day) do update set"
            + " receipts = t.receipts + excluded.receipts, total = t.total + excluded.total",
        BranchFilter.orNone(fact.branchUid()),
        LocalDate.ofInstant(fact.occurredAt(), tenant.zone()),
        Amounts.money(fact.total()));
  }

  @Override
  @Transactional
  public void expense(ExpenseFact fact) {
    if (!firstDelivery(fact.eventId())) {
      return;
    }
    jdbc.update(
        "insert into reporting.daily_expenses as t (tenant_uid, branch_uid, day, category, amount)"
            + " values (platform.current_tenant_uid(), ?, ?, ?, ?)"
            + " on conflict (tenant_uid, branch_uid, day, category) do update set"
            + " amount = t.amount + excluded.amount",
        BranchFilter.orNone(fact.branchUid()),
        fact.date(),
        fact.category(),
        Amounts.money(fact.amount()));
  }

  /** Records the event as handled; false when it was handled before. */
  private boolean firstDelivery(UUID eventId) {
    return jdbc.update(
            "insert into reporting.processed_events (tenant_uid, event_id, processed_at)"
                + " values (platform.current_tenant_uid(), ?, now()) on conflict do nothing",
            eventId)
        == 1;
  }

  private static BigDecimal signed(BigDecimal amount, int sign) {
    BigDecimal money = Amounts.money(amount);
    return sign < 0 ? money.negate() : money;
  }
}
