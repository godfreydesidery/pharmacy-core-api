package tz.co.pharmacy.modules.sales.ports.outbound;

import java.math.BigDecimal;
import java.util.UUID;

/** The tenant's sales settings and its branches' codes and names (tenancy). */
public interface SalesSettings {

  String invoicePrefix();

  BigDecimal maxDiscountPercent();

  String branchCode(UUID branchUid);

  String branchName(UUID branchUid);

  /** Days after a sale its goods may come back without a manager's approval. */
  int returnWindowDays();

  /** A cashier must have an open till shift to sell, refund or issue a voucher. */
  boolean requireOpenShift();

  /**
   * The pharmacy as the tax authority knows it (STD-1): its legal name, whether it is
   * VAT-registered, its TIN and VRN (null when not set).
   */
  default Seller seller() {
    return new Seller(null, true, null, null);
  }

  record Seller(String legalName, boolean vatRegistered, String tin, String vrn) {}
}
