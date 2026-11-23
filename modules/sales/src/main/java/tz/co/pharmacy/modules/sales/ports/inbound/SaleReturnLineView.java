package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A returned line. {@code uid} is the return line's own (to release it); {@code disposition} where
 * its units went; {@code heldQuantity} the units still held in quarantine (0 once released); a
 * released line names its outcome (SHELF or DAMAGED), when and by whom.
 */
public record SaleReturnLineView(
    UUID saleLineUid,
    UUID productUid,
    String productName,
    UUID batchUid,
    String batchNumber,
    int quantity,
    BigDecimal refund,
    List<String> serialNumbers,
    UUID uid,
    ReturnDisposition disposition,
    int heldQuantity,
    ReturnDisposition releasedOutcome,
    Instant releasedAt,
    String releasedBy) {}
