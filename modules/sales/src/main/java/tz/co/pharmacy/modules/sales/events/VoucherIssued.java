package tz.co.pharmacy.modules.sales.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * {@code voucher.issued}: prepaid value was sold; the money came in by {@code paidBy}. {@code code}
 * is the voucher's code masked to its last four characters ({@code ••••7K2Q}): the code itself
 * spends the voucher, and events are kept in one table for every pharmacy (adversarial review
 * ISO-11).
 */
public record VoucherIssued(
    EventMetadata metadata,
    UUID voucherUid,
    String code,
    UUID branchUid,
    BigDecimal value,
    String paidBy)
    implements DomainEvent {

  /** {@code ••••} and the code's last four characters. */
  public static String masked(String code) {
    if (code == null || code.length() <= 4) {
      return "••••";
    }
    return "••••" + code.substring(code.length() - 4);
  }
}
