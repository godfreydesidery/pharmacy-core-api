package tz.co.pharmacy.modules.purchasing.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.purchasing.domain.GoodsReceipt;
import tz.co.pharmacy.modules.purchasing.domain.SupplierReturn;
import tz.co.pharmacy.modules.purchasing.domain.SupplierReturnLine;
import tz.co.pharmacy.modules.purchasing.events.SupplierReturned;
import tz.co.pharmacy.modules.purchasing.persistence.GoodsReceiptRepository;
import tz.co.pharmacy.modules.purchasing.persistence.SupplierReturnRepository;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierReturns;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingDirectory;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
@RequiredArgsConstructor
class SupplierReturnsImpl implements SupplierReturns {

  /** Who may record a return not linked to a goods receipt. */
  static final String MANAGE = "purchasing.manage";

  private final SupplierReturnRepository returns;
  private final GoodsReceiptRepository receipts;
  private final PurchasingDirectory directory;
  private final DocumentNumbers documentNumbers;
  private final DomainEventPublisher events;

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
            field + ".batchUid");
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
                saved.getTotal()));
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
  public Page<SupplierReturnView> list(UUID supplierUid, PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    return Keyset.page(
        returns,
        Specs.<SupplierReturn>equal("branchUid", branch)
            .and(Specs.equal("supplierUid", supplierUid)),
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
            .toList());
  }
}
