package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;

public record StockSummaryView(
    long products,
    long inStock,
    long lowStock,
    long outOfStock,
    long expiringSoon,
    long expired,
    BigDecimal costValue,
    BigDecimal retailValue) {}
