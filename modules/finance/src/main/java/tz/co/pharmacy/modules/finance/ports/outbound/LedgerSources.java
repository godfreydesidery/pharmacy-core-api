package tz.co.pharmacy.modules.finance.ports.outbound;

import java.time.Instant;
import java.util.List;
import tz.co.pharmacy.modules.finance.ports.inbound.UnpostedDocument;

/**
 * The source documents other modules record that the ledger posts after they commit (sales, sale
 * returns, vouchers, goods receipts, supplier returns, customer payments, insurance remittances,
 * stock adjustments), for the reconciliation. Only documents that should have a journal entry (a
 * value other than zero).
 */
public interface LedgerSources {

  /** The current tenant's documents recorded in [from, before), with their journal source type. */
  List<UnpostedDocument> recorded(Instant from, Instant before);
}
