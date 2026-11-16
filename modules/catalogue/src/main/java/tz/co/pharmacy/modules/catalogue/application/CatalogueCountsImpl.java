package tz.co.pharmacy.modules.catalogue.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.catalogue.persistence.ProductRepository;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueCounts;

/** Row-level security keeps the count to the current tenant. */
@Service
class CatalogueCountsImpl implements CatalogueCounts {

  private final ProductRepository products;

  CatalogueCountsImpl(ProductRepository products) {
    this.products = products;
  }

  @Override
  @Transactional(readOnly = true)
  public long productCount() {
    return products.count();
  }
}
