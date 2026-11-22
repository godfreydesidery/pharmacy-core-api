package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Vouchers and gift cards: sold for money (a liability until spent), then spent at the till as a
 * VOUCHER tender whose reference is the code.
 */
public interface Vouchers {

  /** Issues a voucher paid by {@code paidBy} (CASH, MOBILE_MONEY, CARD, BANK_TRANSFER). */
  VoucherView issue(IssueVoucherCommand command);

  Page<VoucherView> list(PageQuery query);

  /** Voids an unspent voucher (refunded or lost); its balance stays as a liability to clear. */
  VoucherView voidVoucher(UUID uid);

  record IssueVoucherCommand(
      String code,
      BigDecimal value,
      LocalDate expiresOn,
      String holderName,
      PaymentMethod paidBy) {}

  record VoucherView(
      UUID uid,
      String code,
      BigDecimal initialValue,
      BigDecimal balance,
      LocalDate expiresOn,
      String holderName,
      String paidBy,
      String status,
      Instant createdAt,
      String createdBy) {}
}
