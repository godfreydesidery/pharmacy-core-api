package tz.co.pharmacy.modules.purchasing.ports.outbound;

import java.util.UUID;

/** Names purchasing keeps on its documents, read from other modules when a document is made. */
public interface PurchasingDirectory {

  /** 422 supplier_not_found. */
  String supplierName(UUID supplierUid);

  /** 422 product_not_found. */
  String productName(UUID productUid);

  String branchCode(UUID branchUid);

  /**
   * The product's tracking mode: BATCH_EXPIRY, BATCH, SERIAL or QUANTITY; 422 product_not_found.
   */
  String trackingMode(UUID productUid);
}
