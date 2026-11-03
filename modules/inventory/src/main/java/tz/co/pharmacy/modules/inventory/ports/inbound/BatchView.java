package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BatchView(
    UUID uid,
    UUID productUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    BigDecimal unitCost,
    Long daysLeft,
    boolean expired) {}
