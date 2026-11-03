package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;
import java.util.List;

public record ProfitLoss(
    BigDecimal revenue,
    BigDecimal costOfSales,
    BigDecimal grossProfit,
    List<ExpenseLine> expenses,
    BigDecimal totalExpenses,
    BigDecimal netProfit) {}
