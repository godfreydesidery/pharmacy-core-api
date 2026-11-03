package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderLineView(
    UUID uid,
    UUID productUid,
    String productName,
    int quantity,
    int receivedQuantity,
    BigDecimal unitCost,
    BigDecimal lineTotal) {}
