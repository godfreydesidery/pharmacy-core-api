package tz.co.pharmacy.modules.purchasing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.purchasing.domain.GoodsReceipt;
import tz.co.pharmacy.modules.purchasing.domain.GoodsReceiptLine;
import tz.co.pharmacy.modules.purchasing.domain.PurchaseOrder;
import tz.co.pharmacy.modules.purchasing.domain.PurchaseOrderLine;
import tz.co.pharmacy.modules.purchasing.events.GoodsReceived;
import tz.co.pharmacy.modules.purchasing.persistence.GoodsReceiptRepository;
import tz.co.pharmacy.modules.purchasing.persistence.PurchaseOrderRepository;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReceiptLineCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.RecordReceiptCommand;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingDirectory;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
@RequiredArgsConstructor
class GoodsReceiptServiceImpl implements GoodsReceiptService {

  private final GoodsReceiptRepository receipts;
  private final PurchaseOrderRepository orders;
  private final PurchasingDirectory directory;
  private final DocumentNumbers documentNumbers;
  private final DomainEventPublisher events;
  private final tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingCurrencies currencies;
  private final TenantCalendar calendar;

  @Override
  @Transactional
  public GoodsReceiptView record(RecordReceiptCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    UUID supplierUid = Checks.required(command.supplierUid(), "supplierUid");
    List<ReceiptLineCommand> lines = Checks.notEmpty(command.lines(), "lines");
    for (int i = 0; i < lines.size(); i++) {
      ReceiptLineCommand line = lines.get(i);
      Checks.required(line.productUid(), "lines[" + i + "].productUid");
      String tracking;
      try {
        tracking = directory.trackingMode(line.productUid());
      } catch (Refusal refusal) {
        throw refusal.about("lines[" + i + "].productUid");
      }
      ReceiptTracking.check(tracking, line, i);
      Checks.positive(line.quantity(), "lines[" + i + "].quantity");
      Checks.notNegative(line.unitCost(), "lines[" + i + "].unitCost");
    }
    // Rounded to the receipt currency's minor units once the receipt is priced (chargeVat).
    BigDecimal vat =
        command.vatAmount() == null
            ? Amounts.ZERO
            : Checks.notNegative(command.vatAmount(), "vatAmount");
    if (vat.signum() > 0 && !directory.vatRegistered()) {
      // STD-2: input VAT is recoverable only by a VAT-registered pharmacy; for one that is not,
      // the VAT is part of what the goods cost, in the lines' unit costs.
      throw Refusal.invalid(
          "vat_not_registered",
          "The pharmacy is not VAT-registered: enter the unit costs with the VAT in them.",
          "vatAmount");
    }
    String invoice =
        Checks.optionalText(command.supplierInvoiceNumber(), "supplierInvoiceNumber", 64);
    if (invoice != null
        && receipts.existsBySupplierUidAndSupplierInvoiceNumberIgnoreCase(supplierUid, invoice)) {
      // The same delivery booked twice doubles stock and what is owed.
      throw Refusal.invalid(
          "duplicate_supplier_invoice",
          "Invoice " + invoice + " from this supplier is already received.",
          "supplierInvoiceNumber");
    }
    PurchaseOrder order = null;
    if (command.purchaseOrderUid() != null) {
      order = orderToReceive(command.purchaseOrderUid(), branch, supplierUid);
      applyToOrder(order, lines);
    }
    GoodsReceipt receipt =
        new GoodsReceipt(
            documentNumbers.format("GRN-" + directory.branchCode(branch), 6),
            branch,
            order,
            supplierUid,
            directory.supplierName(supplierUid),
            invoice,
            Checks.optionalText(command.note(), "note", 1000));
    // A receipt against an order is in the order's currency at its locked rate, unless the rate is
    // overridden here (the supplier invoiced at another rate). Priced first: the lines round to
    // the receipt currency's minor units.
    Priced priced =
        Priced.of(
            currencies,
            command.currency(),
            command.exchangeRate(),
            order == null ? null : order.getCurrency(),
            order == null ? null : order.getExchangeRate());
    receipt.priceIn(priced.currency(), priced.rate());
    for (int i = 0; i < lines.size(); i++) {
      ReceiptLineCommand line = lines.get(i);
      String name;
      try {
        name = directory.productName(line.productUid());
      } catch (Refusal refusal) {
        throw refusal.about("lines[" + i + "].productUid");
      }
      receipt.addLine(
          line.productUid(),
          name,
          ReceiptTracking.batchNumber(line),
          line.expiryDate(),
          line.quantity(),
          line.unitCost(),
          ReceiptTracking.serials(line));
    }
    receipt.chargeVat(vat);
    // STD-17: each amount converted once, half-up to the base currency's minor units, as checkout
    // converts a foreign tender: the payable from the total, the stock from each line, the VAT on
    // its own; the rounding between them is kept and posted (7000).
    BigDecimal baseLines =
        receipt.getLines().stream()
            .map(l -> currencies.toBase(l.getLineTotal(), priced.rate()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    receipt.settleBase(
        currencies.toBase(receipt.getTotal(), priced.rate()),
        baseLines,
        receipt.getVatTotal().signum() == 0
            ? Amounts.ZERO
            : currencies.toBase(receipt.getVatTotal(), priced.rate()));
    GoodsReceipt saved = receipts.save(receipt);
    events.publish(
        "goods.received",
        meta ->
            new GoodsReceived(
                meta,
                saved.getUid(),
                saved.getNumber(),
                saved.getBranchUid(),
                saved.getSupplierUid(),
                saved.getSupplierName(),
                saved.getTotal(),
                saved.getLines().stream().map(GoodsReceiptServiceImpl::eventLine).toList(),
                saved.getCurrency(),
                saved.getExchangeRate(),
                saved.getBaseTotal(),
                saved.getVatTotal(),
                saved.getBaseVatTotal(),
                saved.getBaseRounding()));
    return PurchasingViews.receipt(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<GoodsReceiptView> find(UUID uid) {
    UUID branch = ExecutionContext.requireBranch();
    return receipts
        .findByUid(uid)
        .filter(receipt -> receipt.getBranchUid().equals(branch))
        .map(PurchasingViews::receipt);
  }

  @Override
  @Transactional(readOnly = true)
  public List<GoodsReceiptView> findAll(java.util.Collection<UUID> uids) {
    if (uids == null || uids.isEmpty()) {
      return List.of();
    }
    return receipts.findAllByUidIn(uids).stream().map(PurchasingViews::receipt).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Page<GoodsReceiptView> list(UUID supplierUid, PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    return Keyset.page(
        receipts,
        Specs.<GoodsReceipt>equal("branchUid", branch).and(Specs.equal("supplierUid", supplierUid)),
        query,
        PurchasingViews::receipt);
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal purchasesTotal(Set<UUID> branchUids, LocalDate from, LocalDate to) {
    // The pharmacy's own days (STD-12): in Dar es Salaam a day starts at 21:00 UTC the day before.
    var start = from.atStartOfDay(calendar.zone()).toInstant();
    var end = to.plusDays(1).atStartOfDay(calendar.zone()).toInstant();
    if (branchUids == null) {
      return Amounts.money(receipts.totalBetween(start, end));
    }
    if (branchUids.isEmpty()) {
      return Amounts.ZERO;
    }
    return Amounts.money(receipts.totalBetween(branchUids, start, end));
  }

  /** The open order of this branch and supplier, locked for the receipt. */
  private PurchaseOrder orderToReceive(UUID orderUid, UUID branch, UUID supplierUid) {
    PurchaseOrder order =
        orders
            .lockByUid(orderUid)
            .orElseThrow(
                () ->
                    Refusal.invalid(
                        "purchase_order_not_found", "No such purchase order.", "purchaseOrderUid"));
    if (!order.getBranchUid().equals(branch)) {
      throw Refusal.invalid(
          "purchase_order_other_branch",
          "The purchase order is for another branch.",
          "purchaseOrderUid");
    }
    if (!order.getSupplierUid().equals(supplierUid)) {
      throw Refusal.invalid(
          "supplier_mismatch", "The purchase order is to another supplier.", "supplierUid");
    }
    if (!order.open()) {
      throw Refusal.invalid(
          "purchase_order_closed",
          "The purchase order is " + order.getStatus().name().toLowerCase() + ".",
          "purchaseOrderUid");
    }
    return order;
  }

  /** Fills the order's lines with the receipt's quantities, refusing more than is outstanding. */
  private static void applyToOrder(PurchaseOrder order, List<ReceiptLineCommand> lines) {
    Map<UUID, Integer> received = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      ReceiptLineCommand line = lines.get(i);
      int soFar = received.merge(line.productUid(), line.quantity(), Integer::sum);
      int outstanding =
          order.getLines().stream()
              .filter(orderLine -> orderLine.getProductUid().equals(line.productUid()))
              .mapToInt(PurchaseOrderLine::outstanding)
              .sum();
      boolean ordered =
          order.getLines().stream()
              .anyMatch(orderLine -> orderLine.getProductUid().equals(line.productUid()));
      if (!ordered) {
        throw Refusal.invalid(
            "product_not_on_order",
            "The product is not on the purchase order.",
            "lines[" + i + "].productUid");
      }
      if (soFar > outstanding) {
        throw Refusal.invalid(
            "over_receipt",
            "Only " + outstanding + " of this product are still outstanding on the order.",
            "lines[" + i + "].quantity");
      }
    }
    received.forEach(
        (productUid, quantity) -> {
          int left = quantity;
          for (PurchaseOrderLine orderLine : order.getLines()) {
            if (left == 0) {
              break;
            }
            if (orderLine.getProductUid().equals(productUid)) {
              int portion = Math.min(left, orderLine.outstanding());
              orderLine.receive(portion);
              left -= portion;
            }
          }
        });
    order.updateStatus();
  }

  private static GoodsReceived.Line eventLine(GoodsReceiptLine line) {
    return new GoodsReceived.Line(
        line.getProductUid(), line.getProductName(), line.getQuantity(), line.getUnitCost());
  }
}
