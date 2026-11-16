package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.domain.SupplierPayment;
import tz.co.pharmacy.modules.finance.persistence.SupplierPaymentRepository;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierPaymentCommand;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierPaymentView;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierPayments;
import tz.co.pharmacy.modules.finance.ports.outbound.FinanceCurrencies;
import tz.co.pharmacy.modules.finance.ports.outbound.PayablesDirectory;
import tz.co.pharmacy.modules.finance.ports.outbound.PayablesDirectory.BookedReceipt;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

/**
 * Supplier payments. A payment in a foreign currency costs its amount at today's (or the given)
 * rate in base; the receipts it settles were booked at their own rates. The difference is a
 * realised exchange gain or loss, posted to 7000 FX Gains/Losses with the payment.
 */
@Service
class SupplierPaymentsImpl implements SupplierPayments {

  static final String SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT";

  private final SupplierPaymentRepository payments;
  private final PayablesDirectory directory;
  private final FinanceCurrencies currencies;
  private final DocumentNumbers numbers;
  private final JournalWriter writer;

  SupplierPaymentsImpl(
      SupplierPaymentRepository payments,
      PayablesDirectory directory,
      FinanceCurrencies currencies,
      DocumentNumbers numbers,
      JournalWriter writer) {
    this.payments = payments;
    this.directory = directory;
    this.currencies = currencies;
    this.numbers = numbers;
    this.writer = writer;
  }

  @Override
  @Transactional
  public SupplierPaymentView record(SupplierPaymentCommand command) {
    Checks.required(command, "payment");
    UUID supplierUid = Checks.required(command.supplierUid(), "supplierUid");
    BigDecimal amount = Checks.required(command.amount(), "amount");
    if (amount.signum() <= 0) {
      throw Refusal.invalid("invalid_amount", "amount must be more than zero.", "amount");
    }
    String base = currencies.baseCurrency();
    String currency =
        command.currency() == null || command.currency().isBlank()
            ? base
            : currencies.accepted(command.currency());
    LocalDate paidOn = command.paidOn() == null ? LocalDate.now() : command.paidOn();
    BigDecimal rate;
    if (currency.equals(base)) {
      rate = BigDecimal.ONE;
    } else if (command.exchangeRate() != null) {
      if (command.exchangeRate().signum() <= 0) {
        throw Refusal.invalid(
            "invalid_exchange_rate", "exchangeRate must be more than zero.", "exchangeRate");
      }
      rate = command.exchangeRate();
      checkPlausible(currency, rate, paidOn);
    } else {
      rate = currencies.rateToBase(currency, paidOn);
    }
    List<BookedReceipt> receipts = settled(command.goodsReceiptUids(), supplierUid, currency);
    BigDecimal settledBase = currencies.toBase(amount, rate);
    BigDecimal bookedBase =
        receipts.isEmpty() ? settledBase : currencies.toBase(amount, bookedRate(receipts));
    String method =
        command.paymentMethod() == null || command.paymentMethod().isBlank()
            ? "BANK_TRANSFER"
            : command.paymentMethod().trim().toUpperCase();
    String supplierName = directory.supplierName(supplierUid);
    SupplierPayment payment =
        payments.save(
            new SupplierPayment(
                numbers.format("SPAY", 6),
                supplierUid,
                supplierName,
                amount,
                currency,
                rate,
                settledBase,
                bookedBase,
                method,
                Checks.optionalText(command.reference(), "reference", 120),
                receipts.stream().map(BookedReceipt::uid).toList(),
                paidOn,
                ExecutionContext.branchUid().orElse(null)));
    boolean foreign = !currency.equals(base);
    writer.post(
        SUPPLIER_PAYMENT,
        payment.getUid(),
        payment.getNumber(),
        "Payment to " + supplierName,
        payment.getBranchUid(),
        paidOn,
        Postings.supplierPayment(
            bookedBase, settledBase, method, foreign ? currency : null, foreign ? amount : null));
    return view(payment);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SupplierPaymentView> list(UUID supplierUid, PageQuery query) {
    return Keyset.page(
        payments, Specs.equal("supplierUid", supplierUid), query, SupplierPaymentsImpl::view);
  }

  /**
   * The rate the settled receipts were booked at, weighted by their totals: Σ(total × rate) ÷ Σ
   * total, to ten decimals.
   */
  static BigDecimal bookedRate(List<BookedReceipt> receipts) {
    BigDecimal weighted = BigDecimal.ZERO;
    BigDecimal totals = BigDecimal.ZERO;
    for (BookedReceipt receipt : receipts) {
      weighted = weighted.add(receipt.total().multiply(receipt.exchangeRate()));
      totals = totals.add(receipt.total());
    }
    if (totals.signum() == 0) {
      return receipts.getFirst().exchangeRate();
    }
    return weighted.divide(totals, 10, RoundingMode.HALF_EVEN);
  }

  private List<BookedReceipt> settled(List<UUID> uids, UUID supplierUid, String currency) {
    if (uids == null || uids.isEmpty()) {
      return List.of();
    }
    Map<UUID, BookedReceipt> found =
        directory.receipts(uids).stream()
            .collect(Collectors.toMap(BookedReceipt::uid, Function.identity(), (a, b) -> a));
    for (int i = 0; i < uids.size(); i++) {
      String field = "goodsReceiptUids[" + i + "]";
      BookedReceipt receipt = found.get(uids.get(i));
      if (receipt == null) {
        throw Refusal.invalid("goods_receipt_not_found", "No such goods receipt.", field);
      }
      if (!receipt.supplierUid().equals(supplierUid)) {
        throw Refusal.invalid(
            "supplier_mismatch", receipt.number() + " is from another supplier.", field);
      }
      if (!receipt.currency().equals(currency)) {
        throw Refusal.invalid(
            "currency_mismatch",
            receipt.number() + " is in " + receipt.currency() + "; pay it in that currency.",
            field);
      }
    }
    return uids.stream().distinct().map(found::get).toList();
  }

  static SupplierPaymentView view(SupplierPayment payment) {
    return new SupplierPaymentView(
        payment.getUid(),
        payment.getNumber(),
        payment.getSupplierUid(),
        payment.getSupplierName(),
        payment.getAmount(),
        payment.getCurrency(),
        payment.getExchangeRate().stripTrailingZeros(),
        payment.getBaseAmount(),
        payment.getBookedBaseAmount(),
        payment.getFxDifference(),
        payment.getPaymentMethod(),
        payment.getReference(),
        payment.receiptUids(),
        payment.getPaidOn(),
        payment.getCreatedAt(),
        payment.getCreatedBy());
  }

  /**
   * A typed rate more than 25% away from the recorded rate of the day is refused as a likely
   * mistake (rate 1 for TZS in a USD pharmacy); with no recorded rate the typed one stands.
   */
  private void checkPlausible(String currency, BigDecimal rate, LocalDate on) {
    BigDecimal recorded;
    try {
      recorded = currencies.rateToBase(currency, on);
    } catch (Refusal noRate) {
      return;
    }
    if (recorded == null || recorded.signum() <= 0) {
      return;
    }
    BigDecimal deviation =
        rate.subtract(recorded).abs().divide(recorded, 6, java.math.RoundingMode.HALF_EVEN);
    if (deviation.compareTo(new BigDecimal("0.25")) > 0) {
      throw Refusal.invalid(
          "rate_out_of_range",
          "The rate "
              + rate.stripTrailingZeros().toPlainString()
              + " is more than 25% away from the recorded "
              + currency
              + " rate of "
              + recorded.stripTrailingZeros().toPlainString()
              + ". Check it, or record the new rate under Finance → Exchange rates first.",
          "exchangeRate");
    }
  }
}
