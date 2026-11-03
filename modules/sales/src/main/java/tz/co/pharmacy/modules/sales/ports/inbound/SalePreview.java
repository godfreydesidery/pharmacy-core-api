package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a sale would come to, computed exactly as {@link SaleService#record} computes it, without
 * recording it or refusing a short payment: the total and each line's total, and the base value of
 * each payment ({@code paid}, of which {@code cashPaid} is cash). Checkout uses it to put a balance
 * on account, to split an insured basket and to keep a non-cash excess on account.
 */
public record SalePreview(
    BigDecimal total,
    List<BigDecimal> lineTotals,
    BigDecimal paid,
    BigDecimal cashPaid,
    List<BigDecimal> paymentBaseAmounts) {}
