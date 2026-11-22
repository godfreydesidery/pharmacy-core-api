package tz.co.pharmacy.modules.sales.application;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.sales.domain.Voucher;
import tz.co.pharmacy.modules.sales.events.VoucherIssued;
import tz.co.pharmacy.modules.sales.persistence.VoucherRepository;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.inbound.Vouchers;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesCurrencies;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
@RequiredArgsConstructor
class VouchersImpl implements Vouchers {

  private static final Set<PaymentMethod> PAID_BY =
      EnumSet.of(
          PaymentMethod.CASH,
          PaymentMethod.MOBILE_MONEY,
          PaymentMethod.CARD,
          PaymentMethod.BANK_TRANSFER);
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private final VoucherRepository vouchers;
  private final DomainEventPublisher events;
  private final TenantCalendar calendar;
  private final SalesCurrencies currencies;

  @Override
  @Transactional
  public VoucherView issue(IssueVoucherCommand c) {
    BigDecimal value = Checks.required(c.value(), "value");
    if (value.signum() <= 0) {
      throw Refusal.invalid("invalid_amount", "value must be more than zero.", "value");
    }
    PaymentMethod paidBy = c.paidBy() == null ? PaymentMethod.CASH : c.paidBy();
    if (!PAID_BY.contains(paidBy)) {
      throw Refusal.invalid(
          "invalid_payment_method",
          "A voucher is paid in cash, card, mobile money or bank.",
          "paidBy");
    }
    if (c.expiresOn() != null && c.expiresOn().isBefore(calendar.today())) {
      throw Refusal.invalid("invalid_expiry", "expiresOn has passed.", "expiresOn");
    }
    String code = Checks.optionalText(c.code(), "code", 24);
    if (code == null) {
      do {
        code = generated();
      } while (vouchers.existsByCodeIgnoreCase(code));
    } else if (vouchers.existsByCodeIgnoreCase(code)) {
      throw Refusal.conflict("duplicate_voucher", "A voucher with code " + code + " exists.");
    }
    Voucher voucher =
        vouchers.save(
            new Voucher(
                code.toUpperCase(),
                value,
                c.expiresOn(),
                Checks.optionalText(c.holderName(), "holderName", 120),
                paidBy.name()));
    UUID branch = ExecutionContext.current().map(f -> f.branchUid()).orElse(null);
    events.publish(
        "voucher.issued",
        meta ->
            new VoucherIssued(
                meta,
                voucher.getUid(),
                voucher.getCode(),
                branch,
                voucher.getInitialValue(),
                voucher.getPaidBy()));
    return view(voucher);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<VoucherView> list(PageQuery query) {
    return Keyset.page(vouchers, Specs.all(), query, VouchersImpl::view);
  }

  @Override
  @Transactional
  public VoucherView voidVoucher(UUID uid) {
    Voucher voucher =
        vouchers.findAll().stream()
            .filter(v -> v.getUid().equals(uid))
            .findFirst()
            .orElseThrow(() -> Refusal.notFound("voucher_not_found", "No such voucher."));
    voucher.voided();
    return view(voucher);
  }

  /**
   * Spends {@code amount} of the voucher named by {@code code} at the till; 422 voucher_not_found,
   * voucher_not_active, voucher_expired or voucher_short.
   */
  @Transactional
  void spend(String code, BigDecimal amount, String field) {
    if (code == null || code.isBlank()) {
      throw Refusal.invalid("voucher_code_required", "Enter the voucher code.", field);
    }
    Voucher voucher =
        vouchers
            .lockByCode(code.trim())
            .orElseThrow(
                () -> Refusal.invalid("voucher_not_found", "No voucher " + code + ".", field));
    if (!"ACTIVE".equals(voucher.getStatus())) {
      throw Refusal.invalid(
          "voucher_not_active",
          "Voucher "
              + voucher.getCode()
              + ("VOID".equals(voucher.getStatus()) ? " has been voided." : " has been used up.")
              + " It can no longer be spent.",
          field);
    }
    if (voucher.getExpiresOn() != null && voucher.getExpiresOn().isBefore(calendar.today())) {
      throw Refusal.invalid(
          "voucher_expired",
          "Voucher "
              + voucher.getCode()
              + " expired on "
              + voucher
                  .getExpiresOn()
                  .format(
                      java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
              + ".",
          field);
    }
    if (voucher.getBalance().compareTo(Amounts.money(amount)) < 0) {
      throw Refusal.invalid(
          "voucher_short",
          "Voucher "
              + voucher.getCode()
              + " has only "
              + Amounts.display(currencies.baseCurrency(), voucher.getBalance())
              + " left.",
          field);
    }
    voucher.spend(Amounts.money(amount));
  }

  private String generated() {
    StringBuilder sb = new StringBuilder("V-");
    java.security.SecureRandom random = new java.security.SecureRandom();
    for (int i = 0; i < 8; i++) {
      if (i == 4) {
        sb.append('-');
      }
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }

  private static VoucherView view(Voucher v) {
    return new VoucherView(
        v.getUid(),
        v.getCode(),
        v.getInitialValue(),
        v.getBalance(),
        v.getExpiresOn(),
        v.getHolderName(),
        v.getPaidBy(),
        v.getStatus(),
        v.getCreatedAt(),
        v.getCreatedBy());
  }
}
