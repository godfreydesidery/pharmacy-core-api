package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.finance.ports.outbound.PayablesDirectory;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierDebitNotes;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierReturns;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Binds finance's view of payables to purchasing (receipts as booked) and partners (names). */
@Component
class PayablesDirectoryAdapter implements PayablesDirectory {

  private final GoodsReceiptService receipts;
  private final SupplierService suppliers;
  private final SupplierReturns returns;
  private final SupplierDebitNotes debitNotes;

  PayablesDirectoryAdapter(
      GoodsReceiptService receipts,
      SupplierService suppliers,
      SupplierReturns returns,
      SupplierDebitNotes debitNotes) {
    this.returns = returns;
    this.receipts = receipts;
    this.suppliers = suppliers;
    this.debitNotes = debitNotes;
  }

  /**
   * The receipts as booked, for paying them: a receipt's total includes what its supplier debit
   * notes raised (STD-2), so a payment naming the receipt may settle that too; credited supplier
   * returns came off it (FRD-16).
   */
  @Override
  public List<BookedReceipt> receipts(Collection<UUID> receiptUids) {
    Map<UUID, BigDecimal> credited = returns.creditedAgainst(receiptUids);
    Map<UUID, BigDecimal> raised = debitNotes.raisedAgainst(receiptUids);
    return receipts.findAll(receiptUids).stream()
        .map(
            receipt ->
                new BookedReceipt(
                    receipt.uid(),
                    receipt.number(),
                    receipt.supplierUid(),
                    receipt.currency(),
                    receipt.exchangeRate(),
                    receipt.total().add(raised.getOrDefault(receipt.uid(), BigDecimal.ZERO)),
                    credited.getOrDefault(receipt.uid(), BigDecimal.ZERO)))
        .toList();
  }

  @Override
  public String supplierName(UUID supplierUid) {
    return suppliers
        .find(supplierUid)
        .map(SupplierView::name)
        .orElseThrow(
            () -> Refusal.invalid("supplier_not_found", "No such supplier.", "supplierUid"));
  }
}
