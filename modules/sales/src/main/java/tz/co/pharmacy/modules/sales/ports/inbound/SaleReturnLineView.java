package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SaleReturnLineView(
    UUID saleLineUid,
    UUID productUid,
    String productName,
    UUID batchUid,
    String batchNumber,
    int quantity,
    BigDecimal refund,
    List<String> serialNumbers) {}
