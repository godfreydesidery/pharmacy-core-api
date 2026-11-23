package tz.co.pharmacy.modules.finance.ports.inbound;

import java.util.List;

/**
 * Source documents against the journal (adversarial review MON-13): most entries are posted after
 * their document commits, by an event listener, and a posting that failed would otherwise go
 * unnoticed. Lists the current tenant's documents recorded in the last {@code days} days (1-92),
 * and more than the grace period ago ({@code pharmacy.finance.reconciliation.grace}, 15 minutes),
 * that have no journal entry, oldest first, within the report's branches.
 */
public interface LedgerReconciliation {

  /** 422 invalid_days outside 1-92. */
  List<UnpostedDocument> unposted(int days);

  /**
   * The daily account balances (the read model the balances, trial balance, balance sheet and VAT
   * read) against the journal they come from (PERF-4), for every booking day from {@code days} days
   * ago (1-366) on, future-dated entries included: each account, day and branch whose journal lines
   * do not add up to the read model's row, by day. Empty when they agree. 422 invalid_days outside
   * 1-366.
   */
  List<BalanceMismatch> balanceMismatches(int days);
}
