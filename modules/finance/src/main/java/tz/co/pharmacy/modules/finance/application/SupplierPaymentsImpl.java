package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
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
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.PayableLocks;
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
  private final TenantCalendar calendar;
  private final JdbcTemplate jdbc;

  SupplierPaymentsImpl(
      SupplierPaymentRepository payments,
      PayablesDirectory directory,
      FinanceCurrencies currencies,
      DocumentNumbers numbers,
      JournalWriter writer,
      TenantCalendar calendar,
      DataSource dataSource) {
    this.calendar = calendar;
    this.jdbc = new JdbcTemplate(dataSource);
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
    LocalDate paidOn = ExpenseServiceImpl.notFuture(command.paidOn(), calendar.today(), "paidOn");
    PaidFrom paidFrom =
        PaidFrom.parse(command.paymentMethod(), PaidFrom.BANK_TRANSFER, "paymentMethod");
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
    // The receipts' payables are locked before their open amounts are read: a credit note for a
    // return against one of them waits, and reads what this payment settled (PayableLocks).
    if (command.goodsReceiptUids() != null) {
      PayableLocks.lock(jdbc, command.goodsReceiptUids());
    }
    List<BookedReceipt> receipts = settled(command.goodsReceiptUids(), supplierUid, currency);
    Map<UUID, BigDecimal> allocation = allocate(receipts, amount, currency);
    BigDecimal settledBase = currencies.toBase(amount, rate);
    BigDecimal bookedBase =
        receipts.isEmpty() ? settledBase : currencies.toBase(amount, bookedRate(receipts));
    String method = paidFrom.name();
    String supplierName = directory.supplierName(supplierUid);
    SupplierPayment recorded =
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
            ExecutionContext.branchUid().orElse(null));
    allocation.forEach(recorded::settle);
    SupplierPayment payment = payments.save(recorded);
    boolean foreign = !currency.equals(base);
    writer.post(
        SUPPLIER_PAYMENT,
        payment.getUid(),
        payment.getNumber(),
        "Payment to " + supplierName,
        payment.getBranchUid(),
        paidOn,
        Postings.supplierPayment(
            bookedBase, settledBase, paidFrom, foreign ? currency : null, foreign ? amount : null));
    return view(payment);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SupplierPaymentView> list(UUID supplierUid, PageQuery query) {
    return Keyset.page(
        payments, Specs.equal("supplierUid", supplierUid), query, SupplierPaymentsImpl::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, BigDecimal> settledOf(java.util.Collection<UUID> receiptUids) {
    Map<UUID, BigDecimal> settled = new HashMap<>();
    if (receiptUids == null || receiptUids.isEmpty()) {
      return settled;
    }
    for (Object[] row : payments.settledOf(receiptUids)) {
      settled.put((UUID) row[0], (BigDecimal) row[1]);
    }
    return settled;
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
    return weighted.divide(totals, 10, Amounts.ROUNDING);
  }

  /**
   * What the payment settles of each receipt, in the order named: each takes what is still open of
   * it (its total less what earlier payments settled and credited returns took off), up to what is
   * left of the payment. A payment larger than what is open on the receipts it names is refused
   * (422 overpayment); one naming no receipt is on account. The receipts are locked (a
   * transaction-scoped advisory lock each, in uid order) so two payments at once cannot both settle
   * the same open amount.
   */
  private Map<UUID, BigDecimal> allocate(
      List<BookedReceipt> receipts, BigDecimal amount, String currency) {
    Map<UUID, BigDecimal> allocation = new LinkedHashMap<>();
    if (receipts.isEmpty()) {
      return allocation;
    }
    // The receipts are locked (record, PayableLocks) since before they were read.
    Map<UUID, BigDecimal> settledSoFar =
        settledOf(receipts.stream().map(BookedReceipt::uid).toList());
    // In the payment's currency, the receipts' (USD to the cent, UGX whole).
    BigDecimal left = Amounts.money(amount, currency);
    BigDecimal open = BigDecimal.ZERO;
    for (BookedReceipt receipt : receipts) {
      // Credited supplier returns came off the receipt (FRD-16): no payment settles them again.
      BigDecimal openOfReceipt =
          Amounts.money(receipt.total(), currency)
              .subtract(settledSoFar.getOrDefault(receipt.uid(), BigDecimal.ZERO))
              .subtract(receipt.credited() == null ? BigDecimal.ZERO : receipt.credited())
              .max(BigDecimal.ZERO);
      open = open.add(openOfReceipt);
      BigDecimal portion = left.min(openOfReceipt);
      if (portion.signum() > 0) {
        allocation.put(receipt.uid(), portion);
        left = left.subtract(portion);
      }
    }
    if (left.signum() > 0) {
      throw Refusal.invalid(
              "overpayment",
              "Only "
                  + currency
                  + " "
                  + open.toPlainString()
                  + " is still open on "
                  + receipts.stream().map(BookedReceipt::number).collect(Collectors.joining(", "))
                  + "; pay at most that, or pay the rest on account without naming a receipt.",
              "amount")
          .with("open", open)
          .with(
              "receipts",
              receipts.stream().map(BookedReceipt::number).collect(Collectors.joining(", ")))
          .with("currency", currency);
    }
    return allocation;
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
        rate.subtract(recorded)
            .abs()
            .divide(recorded, 6, tz.co.pharmacy.platform.kernel.Amounts.ROUNDING);
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
