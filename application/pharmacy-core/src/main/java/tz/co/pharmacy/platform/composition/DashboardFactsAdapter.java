package tz.co.pharmacy.platform.composition;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockSummaryView;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.reporting.ports.inbound.StockFigures;
import tz.co.pharmacy.modules.reporting.ports.outbound.DashboardFacts;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Binds the dashboard's live facts: stock figures and expiring batches from inventory, the latest
 * sales from sales and the number of customers from partners; reads only.
 */
@Component
class DashboardFactsAdapter implements DashboardFacts {

  private final tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit receivables;

  private final tz.co.pharmacy.modules.insurance.ports.inbound.Insurance insurance;

  private final StockQueries stock;
  private final SaleService sales;
  private final CustomerService customers;

  DashboardFactsAdapter(
      StockQueries stock,
      SaleService sales,
      CustomerService customers,
      tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit receivables,
      tz.co.pharmacy.modules.insurance.ports.inbound.Insurance insurance) {
    this.stock = stock;
    this.sales = sales;
    this.customers = customers;
    this.receivables = receivables;
    this.insurance = insurance;
  }

  @Override
  public StockFigures stock(Set<UUID> branchUids, int expiryAlertDays) {
    StockSummaryView summary = stock.summary(branchUids, expiryAlertDays);
    return new StockFigures(
        summary.inStock(),
        summary.lowStock(),
        summary.outOfStock(),
        summary.expiringSoon(),
        summary.expired());
  }

  @Override
  public List<Object> expiringBatches(Set<UUID> branchUids, int withinDays, int limit) {
    return new ArrayList<>(stock.expiring(branchUids, withinDays, PageQuery.first(limit)).items());
  }

  @Override
  public List<Object> recentSales(Set<UUID> branchUids, int limit) {
    return new ArrayList<>(sales.recent(branchUids, limit));
  }

  @Override
  public long customers() {
    return customers.count();
  }

  @Override
  public tz.co.pharmacy.modules.reporting.ports.inbound.CreditFigures credit() {
    var totals = receivables.totals(null);
    var claims = insurance.totals();
    return new tz.co.pharmacy.modules.reporting.ports.inbound.CreditFigures(
        totals.outstanding(),
        totals.overdue(),
        totals.unallocatedCredit(),
        totals.accountsWithBalance(),
        claims.pendingClaims(),
        claims.pendingAmount());
  }
}
