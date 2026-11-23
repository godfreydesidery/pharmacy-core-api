package tz.co.pharmacy.modules.finance.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/**
 * A source document with no journal entry: {@code sourceType} as the journal names it (SALE,
 * SALE_RETURN, GOODS_RECEIPT, EXPENSE, SUPPLIER_PAYMENT, CUSTOMER_PAYMENT, STOCK_ADJUSTMENT, …),
 * the document's uid and number, its branch (null for tenant-wide documents) and when it was
 * recorded.
 */
public record UnpostedDocument(
    String sourceType, UUID sourceUid, String number, UUID branchUid, Instant recordedAt) {}
