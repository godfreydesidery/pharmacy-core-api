package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.util.List;

public record DashboardView(
    String currency,
    int periodDays,
    Metric sales,
    Metric purchases,
    Metric grossProfit,
    Metric transactions,
    long customers,
    StockFigures stock,
    List<TrendPoint> salesTrend,
    List<TopProduct> topProducts,
    List<PaymentMix> paymentMix,
    List<Object> expiringBatches,
    List<Object> recentSales,
    CreditFigures credit) {}
