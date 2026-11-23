package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code pricePlanSource}: where the plan came from, CUSTOMER (the customer's or insurer's own plan
 * other than Normal Sale), CHOSEN (another plan, picked by someone holding sales.price_plan), or
 * null (Normal Sale by default).
 */
public record RecordSaleCommand(
    String number,
    String idempotencyKey,
    UUID customerUid,
    String customerName,
    BigDecimal discountPercent,
    List<SaleLineCommand> lines,
    List<PaymentCommand> payments,
    PrescriptionDetails prescription,
    UUID priceListUid,
    String priceListName,
    BigDecimal normalTotal,
    String pricePlanSource) {

  public RecordSaleCommand(
      String number,
      String idempotencyKey,
      UUID customerUid,
      String customerName,
      BigDecimal discountPercent,
      List<SaleLineCommand> lines,
      List<PaymentCommand> payments,
      PrescriptionDetails prescription,
      UUID priceListUid,
      String priceListName,
      BigDecimal normalTotal) {
    this(
        number,
        idempotencyKey,
        customerUid,
        customerName,
        discountPercent,
        lines,
        payments,
        prescription,
        priceListUid,
        priceListName,
        normalTotal,
        null);
  }
}
