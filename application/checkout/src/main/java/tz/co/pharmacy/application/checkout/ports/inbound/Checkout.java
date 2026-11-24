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

  /**
   * Records the return and moves each line's units by its disposition: SHELF back on the batch they
   * were sold from; DAMAGED written off at cost (restored, then a DAMAGE adjustment); QUARANTINE
   * held aside, out of stock, until released.
   */
  SaleReturnView returnSale(RecordReturnCommand command);

  /**
   * Releases the units a return line holds in quarantine: SHELF restocks them (an OTC medicine
   * only), DAMAGED writes them off at cost. See {@code SaleService.releaseReturnLine}.
   */
  SaleReturnView releaseReturnLine(
      UUID returnUid,
      UUID lineUid,
      tz.co.pharmacy.modules.sales.ports.inbound.ReturnDisposition outcome);

  /** Active products, with their quantity on hand at the current branch. */
  Page<SellableProduct> sellable(String q, UUID categoryUid, PageQuery query);

  /**
   * The active products among {@code productUids} (at most 100, else 422 {@code too_many_uids}), in
   * the order asked, each as {@link #sellable(String, UUID, PageQuery)} shows it: a parked sale
   * reads its products again. A product unknown or no longer sold is left out.
   */
  java.util.List<SellableProduct> sellable(java.util.Collection<UUID> productUids);

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
