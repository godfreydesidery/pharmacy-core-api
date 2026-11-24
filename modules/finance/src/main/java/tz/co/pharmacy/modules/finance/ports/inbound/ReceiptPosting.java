package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ReceiptPosting(
    UUID receiptUid,
    String number,
    UUID branchUid,
    LocalDate date,
    /* The receipt's total in the base currency. */
    BigDecimal total,
    /* The receipt's currency and its total in it, noted on the payable; null for base receipts. */
    String currency,
    BigDecimal foreignTotal,
    /* The input VAT in the total, in the base currency (STD-2); null or 0 for none. */
    BigDecimal vat,
    /*
     * The base conversion's rounding (STD-17): total less the goods' and the VAT's base values,
     * each converted once; posted to 7000. Null or 0 for none.
     */
    BigDecimal rounding) {

  public ReceiptPosting(
      UUID receiptUid,
      String number,
      UUID branchUid,
      LocalDate date,
      BigDecimal total,
      String currency,
      BigDecimal foreignTotal,
      BigDecimal vat) {
    this(receiptUid, number, branchUid, date, total, currency, foreignTotal, vat, null);
  }

  public ReceiptPosting(
      UUID receiptUid, String number, UUID branchUid, LocalDate date, BigDecimal total) {
    this(receiptUid, number, branchUid, date, total, null, null, null, null);
  }

  /** A receipt without input VAT. */
  public ReceiptPosting(
      UUID receiptUid,
      String number,
      UUID branchUid,
      LocalDate date,
      BigDecimal total,
      String currency,
      BigDecimal foreignTotal) {
    this(receiptUid, number, branchUid, date, total, currency, foreignTotal, null, null);
  }
}
