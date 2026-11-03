package tz.co.pharmacy.application.receiving.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.application.receiving.ports.inbound.Receiving;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.ReceiveStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReceiptLineView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.RecordReceiptCommand;
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
