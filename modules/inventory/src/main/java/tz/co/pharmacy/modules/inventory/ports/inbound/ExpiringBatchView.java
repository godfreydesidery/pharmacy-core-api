package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A batch in stock near or past its expiry; {@code daysLeft} is negative once expired. */
public record ExpiringBatchView(
    UUID batchUid,
    UUID productUid,
    String productName,
    String batchNumber,
    LocalDate expiryDate,
    long daysLeft,
    int quantity,
    BigDecimal costValue) {}
