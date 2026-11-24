package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Supplier debit notes on goods receipts (STD-2), and the input VAT of receipts and debit notes as
 * the VAT apportionment needs it.
 */
public interface SupplierDebitNotes {

  record DebitNoteView(
      UUID uid,
      String number,
      UUID branchUid,
      UUID goodsReceiptUid,
      String goodsReceiptNumber,
      UUID supplierUid,
      String supplierName,
      String supplierReference,
      String reason,
      BigDecimal net,
      BigDecimal vat,
      BigDecimal total,
      Instant createdAt) {}

  /**
   * Records the supplier's debit note on the receipt: {@code net} before VAT and {@code vat}, base
   * currency. 404 goods_receipt_not_found; 422 invalid_amount, vat_not_registered (VAT while not
   * registered), debit_note_base_currency_only (a receipt in a foreign currency).
   */
  DebitNoteView record(
      UUID goodsReceiptUid, BigDecimal net, BigDecimal vat, String reason, String reference);

  List<DebitNoteView> ofReceipt(UUID goodsReceiptUid);

  /** A product's value on a receipt, base currency, for splitting its input VAT. */
  record LineValue(UUID productUid, BigDecimal value) {}

  /** The input VAT (base) of one receipt or debit note, with its receipt's lines. */
  record InputVat(UUID documentUid, String number, BigDecimal vat, List<LineValue> lines) {}

  /** The debit notes made in [from, to): their net and VAT. */
  record Totals(BigDecimal net, BigDecimal vat) {}

  Totals totals(Instant from, Instant to);

  /** Receipts and supplier debit notes made in [from, to) with VAT, and their receipts' lines. */
  List<InputVat> inputVat(Instant from, Instant to);
}
