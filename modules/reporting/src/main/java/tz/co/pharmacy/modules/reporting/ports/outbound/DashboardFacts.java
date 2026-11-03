package tz.co.pharmacy.modules.reporting.ports.outbound;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import tz.co.pharmacy.modules.reporting.ports.inbound.StockFigures;

/**
 * The dashboard's live facts other modules own: stock (inventory), recent sales (sales) and the
 * number of customers (partners). {@code branchUids} null means every branch.
 */
public interface DashboardFacts {

  StockFigures stock(Set<UUID> branchUids, int expiryAlertDays);

  /** Batches expiring within {@code withinDays}, earliest first. */
  List<Object> expiringBatches(Set<UUID> branchUids, int withinDays, int limit);

  /** The latest sales, newest first. */
  List<Object> recentSales(Set<UUID> branchUids, int limit);

  long customers();

  /** Receivables and pending insurance claims of the tenant. */
  tz.co.pharmacy.modules.reporting.ports.inbound.CreditFigures credit();
}
