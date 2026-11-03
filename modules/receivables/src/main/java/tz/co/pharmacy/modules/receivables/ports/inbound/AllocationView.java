package tz.co.pharmacy.modules.receivables.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

/** Part of a credit (payment, credit note, write-off, account credit) applied to a debit. */
public record AllocationView(UUID entryUid, String reference, UUID saleUid, BigDecimal amount) {}
