package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleView(
    UUID uid,
    String number,
    UUID branchUid,
    String branchName,
    UUID customerUid,
    String customerName,
    SaleStatus status,
    BigDecimal discountPercent,
    BigDecimal subtotal,
    BigDecimal discountTotal,
    BigDecimal taxTotal,
    BigDecimal total,
    BigDecimal paidTotal,
    BigDecimal changeDue,
    List<SaleLineView> lines,
    List<PaymentView> payments,
    PrescriptionDetails prescription,
    String fiscalReference,
    String cashierName,
    Instant createdAt,
    /* The pricing plan it was priced under (null: Normal Sale), as it was named then. */
    UUID priceListUid,
    String priceListName,
    /* CUSTOMER: the customer's own plan (not Normal Sale); CHOSEN: switched at the till. */
    String pricePlanSource,
    /* The fiscal receipt is still to be issued (it is issued after the sale commits). */
    boolean fiscalDue,
    /* Who dispensed its prescription or controlled lines (the seller, holding sales.dispense_rx). */
    String dispensedBy,
    /*
     * The sale has a prescription, but the caller may not read health details (health.view):
     * prescription is null.
     */
    boolean prescriptionWithheld) {

  /** The sale as a caller without health.view sees it: no prescription, a mark that one exists. */
  public SaleView withoutHealthDetails() {
    if (prescription == null) {
      return this;
    }
    return new SaleView(
        uid,
        number,
        branchUid,
        branchName,
        customerUid,
        customerName,
        status,
        discountPercent,
        subtotal,
        discountTotal,
        taxTotal,
        total,
        paidTotal,
        changeDue,
        lines,
        payments,
        null,
        fiscalReference,
        cashierName,
        createdAt,
        priceListUid,
        priceListName,
        pricePlanSource,
        fiscalDue,
        dispensedBy,
        true);
  }
}
