package tz.co.pharmacy.modules.sales.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * How a refund goes back: the way the sale was paid. Each tender (a method, and for a voucher its
 * code) gets back its share of the sale, so the cash share goes back in cash, a mobile money or
 * card share by that method, a credit share to the customer's account and the insurer's share off
 * the claim. Shares are cumulative: after a return each tender has had back {@code kept × refunded
 * so far ÷ sale total}, rounded to cents, less what earlier returns gave it; when the sale is fully
 * returned each tender has had back exactly what it kept, so no cent is left over or paid twice.
 */
public final class RefundSplit {

  private RefundSplit() {}

  /** A way a sale was paid; {@code reference} names the voucher for VOUCHER, else null. */
  public record Tender(PaymentMethod method, String reference) {

    public Tender {
      reference =
          method == PaymentMethod.VOUCHER && reference != null
              ? reference.trim().toUpperCase()
              : null;
    }
  }

  public record Share(Tender tender, BigDecimal amount) {}

  /**
   * What each tender kept of the sale, in base: its payments less, for cash, the change handed back
   * (change comes out of cash; with no cash tendered, out of the first tender, as the ledger posts
   * it).
   */
  public static Map<Tender, BigDecimal> kept(Sale sale) {
    Map<Tender, BigDecimal> kept = new LinkedHashMap<>();
    for (SalePayment payment : sale.getPayments()) {
      BigDecimal base =
          payment.getBaseAmount() == null ? payment.getAmount() : payment.getBaseAmount();
      kept.merge(new Tender(payment.getMethod(), payment.getReference()), base, BigDecimal::add);
    }
    BigDecimal change = sale.getChangeDue() == null ? Amounts.ZERO : sale.getChangeDue();
    if (change.signum() > 0 && !kept.isEmpty()) {
      Tender from =
          kept.keySet().stream()
              .filter(t -> t.method() == PaymentMethod.CASH)
              .findFirst()
              .orElse(kept.keySet().iterator().next());
      kept.merge(from, change.negate(), BigDecimal::add);
    }
    kept.replaceAll((t, v) -> Amounts.money(v));
    kept.values().removeIf(v -> v.signum() <= 0);
    return kept;
  }

  /**
   * Splits {@code refund} over the tenders. {@code saleTotal} is the sale's total; {@code
   * refundedBefore} what earlier returns of the sale gave back, by tender, and {@code
   * refundedBeforeTotal} their total; {@code completes} whether this return brings back the last
   * unit of the sale.
   */
  public static List<Share> split(
      Map<Tender, BigDecimal> kept,
      BigDecimal saleTotal,
      Map<Tender, BigDecimal> refundedBefore,
      BigDecimal refundedBeforeTotal,
      BigDecimal refund,
      boolean completes) {
    BigDecimal amount = Amounts.money(refund);
    if (amount.signum() <= 0 || kept.isEmpty()) {
      return List.of();
    }
    BigDecimal keptTotal = kept.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal basis = saleTotal == null || saleTotal.signum() <= 0 ? keptTotal : saleTotal;
    BigDecimal after = Amounts.money(refundedBeforeTotal).add(amount);
    Map<Tender, BigDecimal> shares = new LinkedHashMap<>();
    for (Map.Entry<Tender, BigDecimal> tender : kept.entrySet()) {
      BigDecimal target =
          completes || after.compareTo(basis) >= 0
              ? tender.getValue()
              : Amounts.money(
                  tender
                      .getValue()
                      .multiply(after)
                      .divide(basis, 6, RoundingMode.HALF_UP)
                      .min(tender.getValue()));
      BigDecimal before = refundedBefore.getOrDefault(tender.getKey(), Amounts.ZERO);
      shares.put(tender.getKey(), Amounts.money(target.subtract(before).max(BigDecimal.ZERO)));
    }
    // A cent of rounding, or earlier returns refunded under other rules: the difference goes to
    // the tender with the most room left, or comes off the largest share, never below zero.
    BigDecimal difference =
        amount.subtract(shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    while (difference.signum() != 0) {
      Tender pick = null;
      BigDecimal best = null;
      for (Map.Entry<Tender, BigDecimal> share : shares.entrySet()) {
        BigDecimal room =
            difference.signum() > 0
                ? kept.get(share.getKey())
                    .subtract(refundedBefore.getOrDefault(share.getKey(), Amounts.ZERO))
                    .subtract(share.getValue())
                : share.getValue();
        if (best == null || room.compareTo(best) > 0) {
          best = room;
          pick = share.getKey();
        }
      }
      BigDecimal step =
          difference.signum() > 0
              ? (best.signum() > 0 ? difference.min(best) : difference)
              : difference.max(best.negate());
      if (step.signum() == 0) {
        break;
      }
      shares.merge(pick, step, BigDecimal::add);
      difference = difference.subtract(step);
    }
    List<Share> out = new ArrayList<>();
    shares.forEach(
        (tender, value) -> {
          if (value.signum() > 0) {
            out.add(new Share(tender, Amounts.money(value)));
          }
        });
    return out;
  }
}
