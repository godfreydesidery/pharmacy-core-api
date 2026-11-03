package tz.co.pharmacy.modules.reporting.ports.inbound;

public record StockFigures(
    long inStock, long lowStock, long outOfStock, long expiringSoon, long expired) {}
