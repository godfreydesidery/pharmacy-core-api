package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The part of a request taken from one batch, earliest expiry first; {@code serialNumbers} are the
 * units taken of a SERIAL product, empty otherwise.
 */
public record Allocation(
    UUID batchUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    BigDecimal unitCost,
    List<String> serialNumbers) {}
