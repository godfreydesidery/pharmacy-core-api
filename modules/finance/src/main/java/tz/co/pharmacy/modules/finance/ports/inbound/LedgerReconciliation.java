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
}
