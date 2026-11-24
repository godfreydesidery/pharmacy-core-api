package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Sales debit notes (STD-2): charging a sale's customer more after the invoice. The caller charges
 * the customer's account in the same transaction; the ledger posts after commit ({@code
 * sale.debit_note_issued}).
 */
public interface SaleDebitNotes {

  /**
   * A debit note on the sale for {@code net} (before VAT) of {@code taxCategory} (null: STANDARD).
   * 422 debit_note_needs_customer for a sale without a customer, invalid_amount for a net of zero
   * or less; 404 sale_not_found.
   */
  DebitNoteView issue(UUID saleUid, BigDecimal net, String taxCategory, String reason);

  List<DebitNoteView> ofSale(UUID saleUid);

  /** Issues the note's fiscal document when one is due (after commit). */
  void fiscalise(UUID debitNoteUid);
}
