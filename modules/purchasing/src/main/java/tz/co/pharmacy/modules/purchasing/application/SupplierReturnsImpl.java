package tz.co.pharmacy.modules.purchasing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.purchasing.domain.GoodsReceipt;
import tz.co.pharmacy.modules.purchasing.domain.SupplierReturn;
import tz.co.pharmacy.modules.purchasing.domain.SupplierReturnLine;
import tz.co.pharmacy.modules.purchasing.events.SupplierCredited;
import tz.co.pharmacy.modules.purchasing.events.SupplierReturned;
import tz.co.pharmacy.modules.purchasing.persistence.GoodsReceiptRepository;
import tz.co.pharmacy.modules.purchasing.persistence.SupplierReturnRepository;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierReturns;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PayableSettlements;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingCurrencies;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingDirectory;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.PayableLocks;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
class SupplierReturnsImpl implements SupplierReturns {

  /** Who may record a return not linked to a goods receipt. */
  static final String MANAGE = "purchasing.manage";

  private final SupplierReturnRepository returns;
  private final GoodsReceiptRepository receipts;
  private final PurchasingDirectory directory;
  private final DocumentNumbers documentNumbers;
  private final DomainEventPublisher events;
  private final PurchasingCurrencies currencies;
  private final PayableSettlements settlements;
  private final TenantCalendar calendar;
  private final JdbcTemplate jdbc;

  @SuppressWarnings("java:S107")
  SupplierReturnsImpl(
      SupplierReturnRepository returns,
      GoodsReceiptRepository receipts,
      PurchasingDirectory directory,
      DocumentNumbers documentNumbers,
      DomainEventPublisher events,
      PurchasingCurrencies currencies,
      PayableSettlements settlements,
      TenantCalendar calendar,
      DataSource dataSource) {
    this.returns = returns;
    this.receipts = receipts;
    this.directory = directory;
    this.documentNumbers = documentNumbers;
    this.events = events;
    this.currencies = currencies;
    this.settlements = settlements;
    this.calendar = calendar;
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  @Transactional
  public SupplierReturnView record(RecordSupplierReturnCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    Checks.required(command, "body");
    UUID supplierUid = Checks.required(command.supplierUid(), "supplierUid");
    String supplierName = directory.supplierName(supplierUid);
    GoodsReceipt receipt = null;
    if (command.goodsReceiptUid() != null) {
      receipt =
          receipts
              .findByUid(command.goodsReceiptUid())
              .orElseThrow(
                  () ->
                      Refusal.invalid(
                          "goods_receipt_not_found", "No such goods receipt.", "goodsReceiptUid"));
      if (!receipt.getSupplierUid().equals(supplierUid)) {
        throw Refusal.invalid(
            "receipt_supplier_mismatch",
            "The goods receipt is from another supplier.",
            "goodsReceiptUid");
      }
    } else if (!ExecutionContext.permits(MANAGE)) {
      // Stock sent back with no receipt to check it against is a way out for any stock: only a
      // purchasing manager records one (adversarial review FRD-16).
      throw Refusal.forbidden(
          "supplier_return_needs_receipt",
          "Choose the goods receipt the stock came on; a return without one needs the"
              + " permission to manage purchasing (purchasing.manage).");
    }
    List<Line> lines = Checks.notEmpty(command.lines(), "lines");
    SupplierReturn supplierReturn =
        new SupplierReturn(
            documentNumbers.format("RTS-" + directory.branchCode(branch), 6),
            branch,
            supplierUid,
            supplierName,
            receipt == null ? null : receipt.getUid(),
            receipt == null ? null : receipt.getNumber(),
            Checks.text(command.reason(), "reason", 500),
            Checks.optionalText(command.note(), "note", 1000));
    supplierReturn.recordedBy(ExecutionContext.actor().name());
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      Line line = lines.get(i);
      String field = "lines[" + i + "]";
      UUID batchUid = Checks.required(line.batchUid(), field + ".batchUid");
      Checks.positive(line.quantity(), field + ".quantity");
      if (!seen.add(batchUid)) {
        throw Refusal.invalid("duplicate_batch", "Each batch appears once.", field + ".batchUid");
      }
      String name;
      try {
        name = directory.productName(line.productUid());
      } catch (Refusal refusal) {
        throw refusal.about(field + ".productUid");
      }
      if (receipt != null && !onReceipt(receipt, line)) {
        throw Refusal.invalid(
                "batch_not_on_receipt",
                "Batch "
                    + line.batchNumber()
                    + " of "
                    + name
                    + " was not received on "
                    + receipt.getNumber()
                    + "; choose the goods receipt it came on, or none.",
                field + ".batchUid")
            .with("batch", line.batchNumber())
            .with("product", name)
            .with("expiry", line.expiryDate())
            .with("receipt", receipt.getNumber());
      }
      supplierReturn.addLine(
          new SupplierReturnLine(
              line.productUid(),
              name,
              batchUid,
              line.batchNumber(),
              line.expiryDate(),
              line.quantity(),
              Checks.notNegative(line.unitCost(), field + ".unitCost")));
    }
    SupplierReturn saved = returns.save(supplierReturn);
    events.publish(
        "supplier.returned",
        meta ->
            new SupplierReturned(
                meta,
                saved.getUid(),
                saved.getNumber(),
                saved.getBranchUid(),
                saved.getSupplierUid(),
                saved.getSupplierName(),
                saved.getTotal(),
                true));
    return view(saved);
  }

  /** Whether the receipt took in this batch of the product (by its batch number). */
  private static boolean onReceipt(GoodsReceipt receipt, Line line) {
    return receipt.getLines().stream()
        .anyMatch(
            r ->
                r.getProductUid().equals(line.productUid())
                    && r.getBatchNumber() != null
                    && r.getBatchNumber().equalsIgnoreCase(String.valueOf(line.batchNumber())));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SupplierReturnView> list(UUID supplierUid, String status, PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase();
    if (wanted != null
        && !wanted.equals(SupplierReturn.PENDING_CREDIT)
        && !wanted.equals(SupplierReturn.CREDITED)) {
      throw Refusal.invalid("invalid_status", "status is PENDING_CREDIT or CREDITED.", "status");
    }
    return Keyset.page(
        returns,
        Specs.<SupplierReturn>equal("branchUid", branch)
            .and(Specs.equal("supplierUid", supplierUid))
            .and(Specs.equal("status", wanted)),
        query,
        SupplierReturnsImpl::view);
  }

  @Override
  @Transactional(readOnly = true)
  public SupplierReturnView get(UUID uid) {
    return returns
        .findByUid(uid)
        .filter(r -> r.getBranchUid().equals(ExecutionContext.requireBranch()))
        .map(SupplierReturnsImpl::view)
        .orElseThrow(
            () -> Refusal.notFound("supplier_return_not_found", "No such supplier return."));
  }

  @Override
  @Transactional
  public SupplierReturnView credit(UUID returnUid, CreditNoteCommand command) {
    Checks.required(command, "body");
    String number = Checks.text(command.number(), "number", 64);
    BigDecimal given = Checks.required(command.amount(), "amount");
    if (given.signum() <= 0) {
      throw Refusal.invalid("invalid_amount", "amount must be more than zero.", "amount");
    }
    LocalDate today = calendar.today();
    LocalDate date = command.date() == null ? today : command.date();
    if (date.isAfter(today)) {
      throw Refusal.invalid(
          "future_date", "The credit note's date cannot be after today (" + today + ").", "date");
    }
    SupplierReturn found =
        returns
            .findByUid(returnUid)
            .filter(r -> ExecutionContext.mayUseBranch(r.getBranchUid()))
            .orElseThrow(
                () -> Refusal.notFound("supplier_return_not_found", "No such supplier return."));
    GoodsReceipt receipt =
        found.getGoodsReceiptUid() == null
            ? null
            : receipts.findByUid(found.getGoodsReceiptUid()).orElse(null);
    // The receipt's payable first, then the return (the order a payment takes them in).
    if (receipt != null) {
      PayableLocks.lock(jdbc, List.of(receipt.getUid()));
    }
    SupplierReturn supplierReturn = returns.lockByUid(returnUid).orElseThrow();
    if (!supplierReturn.pendingCredit()) {
      throw Refusal.conflict(
          "return_already_credited",
          supplierReturn.getNumber()
              + " already has the supplier's credit note"
              + (supplierReturn.getCreditNoteNumber() == null
                  ? "."
                  : " " + supplierReturn.getCreditNoteNumber() + "."));
    }
    if (returns.creditNoteRecorded(supplierReturn.getSupplierUid(), number)) {
      throw Refusal.invalid(
              "duplicate_credit_note",
              "Credit note " + number + " from this supplier is already recorded.",
              "number")
          .with("number", number);
    }
    String currency = receipt == null ? currencies.baseCurrency() : receipt.getCurrency();
    BigDecimal amount = Amounts.money(given, currency);
    BigDecimal applied = Amounts.money(BigDecimal.ZERO, currency);
    BigDecimal base;
    if (receipt != null) {
      BigDecimal total = Amounts.money(receipt.getTotal(), currency);
      if (amount.compareTo(total) > 0) {
        throw Refusal.invalid(
                "credit_exceeds_receipt",
                "The credit note is more than "
                    + receipt.getNumber()
                    + " was for ("
                    + Amounts.display(currency, total)
                    + ").",
                "amount")
            .with("receipt", receipt.getNumber())
            .with("total", total)
            .with("currency", currency);
      }
      applied = amount.min(open(receipt).max(BigDecimal.ZERO));
      // At the rate the receipt was booked at: the payable comes down as it went up.
      base = currencies.toBase(amount, receipt.getExchangeRate());
    } else {
      base = Amounts.money(amount);
    }
    supplierReturn.credit(
        number,
        date,
        currency,
        amount,
        base,
        applied,
        Clocks.now(),
        ExecutionContext.actor().reference(),
        ExecutionContext.actor().uid());
    SupplierReturn saved = returns.saveAndFlush(supplierReturn);
    BigDecimal creditBase = base;
    events.publish(
        "supplier.credited",
        meta ->
            new SupplierCredited(
                meta,
                saved.getUid(),
                saved.getNumber(),
                saved.getBranchUid(),
                saved.getSupplierUid(),
                number,
                saved.getTotal(),
                creditBase,
                currency,
                amount));
    return view(saved);
  }

  /**
   * What is still open of the receipt, in its currency: its total, plus what supplier debit notes
   * raised on it (STD-2), less payments and credits.
   */
  private BigDecimal open(GoodsReceipt receipt) {
    List<UUID> uid = List.of(receipt.getUid());
    BigDecimal paid = settlements.settledByPayments(uid).get(receipt.getUid());
    BigDecimal credited = creditedAgainst(uid).get(receipt.getUid());
    BigDecimal raised =
        jdbc.queryForObject(
            "select coalesce(sum(total), 0) from purchasing.supplier_debit_notes"
                + " where goods_receipt_uid = ?",
            BigDecimal.class,
            receipt.getUid());
    return Amounts.money(receipt.getTotal(), receipt.getCurrency())
        .add(raised == null ? BigDecimal.ZERO : raised)
        .subtract(paid == null ? BigDecimal.ZERO : paid)
        .subtract(credited == null ? BigDecimal.ZERO : credited);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, BigDecimal> creditedAgainst(Collection<UUID> receiptUids) {
    Map<UUID, BigDecimal> credited = new HashMap<>();
    if (receiptUids == null || receiptUids.isEmpty()) {
      return credited;
    }
    for (Object[] row : returns.creditedAgainst(receiptUids)) {
      credited.put((UUID) row[0], (BigDecimal) row[1]);
    }
    return credited;
  }

  private static SupplierReturnView view(SupplierReturn r) {
    return new SupplierReturnView(
        r.getUid(),
        r.getNumber(),
        r.getBranchUid(),
        r.getSupplierUid(),
        r.getSupplierName(),
        r.getGoodsReceiptUid(),
        r.getGoodsReceiptNumber(),
        r.getReason(),
        r.getNote(),
        r.getTotal(),
        r.getCreatedAt(),
        r.getCreatedBy(),
        r.getCreatedByName() == null ? r.getCreatedBy() : r.getCreatedByName(),
        r.getLines().stream()
            .map(
                l ->
                    new LineView(
                        l.getProductUid(),
                        l.getProductName(),
                        l.getBatchUid(),
                        l.getBatchNumber(),
                        l.getExpiryDate(),
                        l.getQuantity(),
                        l.getUnitCost(),
                        l.getLineTotal()))
            .toList(),
        r.getStatus(),
        r.getCreditAmount() == null
            ? null
            : new CreditNoteView(
                r.getCreditNoteNumber(),
                r.getCreditNoteDate(),
                r.getCreditCurrency(),
                r.getCreditAmount(),
                r.getCreditBaseAmount(),
                r.getCreditApplied(),
                r.getCreditedAt(),
                r.getCreditedBy()));
  }
}
