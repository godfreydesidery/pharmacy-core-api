package tz.co.pharmacy.modules.purchasing.ports.outbound;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** What supplier payments have settled of goods receipts (finance), for their open amounts. */
public interface PayableSettlements {

  /** Per receipt, in its currency, what payments settled of it; receipts with none are absent. */
  Map<UUID, BigDecimal> settledByPayments(Collection<UUID> receiptUids);
}
