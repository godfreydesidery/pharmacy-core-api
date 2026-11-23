package tz.co.pharmacy.application.checkout.ports.inbound;

import java.util.UUID;
import tz.co.pharmacy.modules.sales.ports.inbound.RecordReturnCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The POS at the current branch. */
public interface Checkout {

  /**
   * Sells the cart: prices from the catalogue, stock taken earliest expiry first, the sale
   * recorded, in one transaction. An existing {@code idempotencyKey} returns that sale.
   */
  SaleView checkout(CheckoutCommand command);

  /** Records the return and puts the units back on the batches they were sold from. */
  SaleReturnView returnSale(RecordReturnCommand command);

  /** Active products, with their quantity on hand at the current branch. */
  Page<SellableProduct> sellable(String q, UUID categoryUid, PageQuery query);

  /**
   * How a basket splits between the member's insurer and the patient, priced as checkout would
   * price it; for the POS coverage panel.
   */
  default tz.co.pharmacy.modules.insurance.ports.inbound.CoverageQuote coverageQuote(
      UUID memberUid, java.util.List<CheckoutLine> lines, String preauthNumber) {
    return coverageQuote(memberUid, lines, preauthNumber, null, null, null);
  }

  /**
   * As checkout prices the sale: {@code customerUid} (the member must be theirs, 422
   * member_not_of_customer), so the insurer's or the customer's plan applies; {@code pricePlan} and
   * {@code discountPercent} as the sale will send them. Without a customer the basket is priced at
   * Normal Sale (branch prices, promotions, quantity breaks).
   */
  tz.co.pharmacy.modules.insurance.ports.inbound.CoverageQuote coverageQuote(
      UUID memberUid,
      java.util.List<CheckoutLine> lines,
      String preauthNumber,
      UUID customerUid,
      String pricePlan,
      java.math.BigDecimal discountPercent);
}
