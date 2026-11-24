package tz.co.pharmacy.application.receiving.internal;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.application.receiving.ports.inbound.Receiving;
import tz.co.pharmacy.modules.inventory.ports.inbound.BatchView;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.ReceiveStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReceiptLineView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.RecordReceiptCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierReturns;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierReturns.SupplierReturnView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * One transaction: purchasing records the receipt (and fills its purchase order), then inventory
 * takes each line into stock under the receipt's number. A refusal from either leaves neither.
 */
@Service
@RequiredArgsConstructor
class ReceivingService implements Receiving {

  private final GoodsReceiptService goodsReceipts;
  private final StockService stock;
  private final StockQueries stockQueries;
  private final SupplierReturns supplierReturns;

  @Override
  @Transactional
  public SupplierReturnView returnToSupplier(ReturnToSupplierCommand command) {
    Checks.required(command, "body");
    List<ReturnLine> given = Checks.notEmpty(command.lines(), "lines");
    List<SupplierReturns.Line> lines = new ArrayList<>();
    for (int i = 0; i < given.size(); i++) {
      ReturnLine line = given.get(i);
      String field = "lines[" + i + "]";
      Checks.required(line.productUid(), field + ".productUid");
      Checks.required(line.batchUid(), field + ".batchUid");
      int quantity =
          Checks.positive(line.quantity() == null ? 0 : line.quantity(), field + ".quantity");
      BatchView batch =
          stockQueries.batches(line.productUid()).stream()
              .filter(b -> b.uid().equals(line.batchUid()))
              .findFirst()
              .orElseThrow(
                  () ->
                      Refusal.invalid(
                          "batch_not_in_stock",
                          "That batch of the product is not in stock at this branch.",
                          field + ".batchUid"));
      if (batch.quantity() < quantity) {
        throw Refusal.invalid(
                "insufficient_stock",
                "Only " + batch.quantity() + " of batch " + batch.batchNumber() + " are in stock.",
                field + ".quantity")
            .with("batch", batch.batchNumber())
            .with("available", batch.quantity())
            .with("requested", quantity);
      }
      lines.add(
          new SupplierReturns.Line(
              line.productUid(),
              batch.uid(),
              batch.batchNumber(),
              batch.expiryDate(),
              quantity,
              batch.unitCost()));
    }
    SupplierReturnView recorded =
        supplierReturns.record(
            new SupplierReturns.RecordSupplierReturnCommand(
                command.supplierUid(),
                command.goodsReceiptUid(),
                command.reason(),
                command.note(),
                lines));
    for (int i = 0; i < lines.size(); i++) {
      try {
        stock.returnToSupplier(lines.get(i).batchUid(), lines.get(i).quantity(), recorded.number());
      } catch (Refusal refusal) {
        throw refusal.about("lines[" + i + "].quantity");
      }
    }
    return recorded;
  }

  @Override
  @Transactional
  public GoodsReceiptView receive(RecordReceiptCommand command) {
    GoodsReceiptView receipt = goodsReceipts.record(command);
    for (int i = 0; i < receipt.lines().size(); i++) {
      ReceiptLineView line = receipt.lines().get(i);
      try {
        stock.receive(
            new ReceiveStockCommand(
                line.productUid(),
                line.batchNumber(),
                line.expiryDate(),
                line.quantity(),
                // Stock is always costed in the base currency, at the receipt's locked rate.
                line.baseUnitCost(),
                MovementType.RECEIPT,
                receipt.number(),
                line.serialNumbers()));
      } catch (Refusal refusal) {
        throw refusal.field() == null || refusal.field().startsWith("lines[")
            ? refusal
            : refusal.about("lines[" + i + "]." + refusal.field());
      }
    }
    return receipt;
  }
}
