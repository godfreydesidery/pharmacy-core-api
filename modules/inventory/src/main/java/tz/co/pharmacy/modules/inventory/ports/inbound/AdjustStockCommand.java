package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * A signed change to one batch at the current branch, with its reason. For a SERIAL product an
 * increase names the new units' serial numbers; a decrease may name the units written off (else the
 * oldest in stock are).
 */
public record AdjustStockCommand(
    UUID productUid,
    UUID batchUid,
    int quantityChange,
    AdjustmentReason reasonCode,
    String note,
    List<String> serialNumbers) {}
