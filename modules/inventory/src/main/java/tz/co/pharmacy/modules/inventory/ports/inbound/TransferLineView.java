package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TransferLineView(
    UUID productUid,
    String productName,
    UUID batchUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    List<String> serialNumbers) {}
