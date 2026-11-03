package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SaleLineView(
    UUID uid,
    UUID productUid,
    String productName,
    UUID batchUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    int returnedQuantity,
    BigDecimal unitPrice,
    BigDecimal discount,
    BigDecimal tax,
    BigDecimal lineTotal,
    List<String> serialNumbers,
    LocalDate warrantyEndsOn,
    List<String> returnedSerialNumbers) {}
