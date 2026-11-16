package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.util.UUID;

/**
 * {@code q} matches name, generic name, SKU, GTIN, brand or model; {@code categoryUid} includes the
 * category's descendants; {@code schedule} narrows to OTC, PRESCRIPTION or CONTROLLED items; null
 * fields do not filter.
 */
public record ProductFilter(
    String q, UUID categoryUid, Boolean active, ProductType productType, Schedule schedule) {

  public ProductFilter(String q, UUID categoryUid, Boolean active, ProductType productType) {
    this(q, categoryUid, active, productType, null);
  }
}
