package tz.co.pharmacy.application.receiving.ports.inbound;

import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.RecordReceiptCommand;

/** Receives goods at the current branch: the receipt and the stock it brings, together. */
public interface Receiving {

  GoodsReceiptView receive(RecordReceiptCommand command);
}
