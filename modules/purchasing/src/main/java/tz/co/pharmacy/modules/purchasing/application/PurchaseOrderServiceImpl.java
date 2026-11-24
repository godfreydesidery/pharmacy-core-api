package tz.co.pharmacy.modules.purchasing.application;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.purchasing.domain.PurchaseOrder;
import tz.co.pharmacy.modules.purchasing.persistence.PurchaseOrderRepository;
import tz.co.pharmacy.modules.purchasing.ports.inbound.CreatePurchaseOrderCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.OrderLineCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderStatus;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderView;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingDirectory;
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
class PurchaseOrderServiceImpl implements PurchaseOrderService {

  private final PurchaseOrderRepository orders;
  private final PurchasingDirectory directory;
  private final DocumentNumbers documentNumbers;
  private final tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingCurrencies currencies;

  @Override
  @Transactional
  public PurchaseOrderView create(CreatePurchaseOrderCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    UUID supplierUid = Checks.required(command.supplierUid(), "supplierUid");
    List<OrderLineCommand> lines = Checks.notEmpty(command.lines(), "lines");
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      OrderLineCommand line = lines.get(i);
      Checks.required(line.productUid(), "lines[" + i + "].productUid");
      Checks.positive(line.quantity(), "lines[" + i + "].quantity");
      Checks.notNegative(line.unitCost(), "lines[" + i + "].unitCost");
      if (!seen.add(line.productUid())) {
        throw Refusal.invalid(
            "duplicate_product", "Each product appears once.", "lines[" + i + "].productUid");
      }
    }
    PurchaseOrder order =
        new PurchaseOrder(
            documentNumbers.format("PO-" + directory.branchCode(branch), 6),
            branch,
            supplierUid,
            directory.supplierName(supplierUid),
            command.expectedDate(),
            Checks.optionalText(command.note(), "note", 1000));
    // Priced first: the lines round to the order currency's minor units.
    Priced priced = Priced.of(currencies, command.currency(), command.exchangeRate(), null, null);
    order.priceIn(priced.currency(), priced.rate());
    for (int i = 0; i < lines.size(); i++) {
      OrderLineCommand line = lines.get(i);
      String name;
      try {
        name = directory.productName(line.productUid());
      } catch (Refusal refusal) {
        throw refusal.about("lines[" + i + "].productUid");
      }
      order.addLine(line.productUid(), name, line.quantity(), line.unitCost());
    }
    order.settleBase(currencies.toBase(order.getTotal(), priced.rate()));
    return PurchasingViews.order(orders.save(order));
  }

  @Override
  @Transactional
  public PurchaseOrderView cancel(UUID uid, String reason) {
    PurchaseOrder order = ofBranch(orders.lockByUid(uid));
    if (order.getStatus() != PurchaseOrderStatus.ORDERED) {
      throw Refusal.invalid(
          "not_cancellable", "Only an order with nothing received yet can be cancelled.");
    }
    order.cancel(Checks.optionalText(reason, "reason", 500));
    return PurchasingViews.order(order);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PurchaseOrderView> find(UUID uid) {
    UUID branch = ExecutionContext.requireBranch();
    return orders
        .findByUid(uid)
        .filter(order -> order.getBranchUid().equals(branch))
        .map(PurchasingViews::order);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PurchaseOrderView> list(
      PurchaseOrderStatus status, UUID supplierUid, PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    return Keyset.page(
        orders,
        Specs.<PurchaseOrder>equal("branchUid", branch)
            .and(Specs.equal("status", status))
            .and(Specs.equal("supplierUid", supplierUid)),
        query,
        PurchasingViews::order);
  }

  private PurchaseOrder ofBranch(Optional<PurchaseOrder> order) {
    UUID branch = ExecutionContext.requireBranch();
    return order
        .filter(found -> found.getBranchUid().equals(branch))
        .orElseThrow(() -> Refusal.notFound("purchase_order_not_found", "No such purchase order."));
  }
}
