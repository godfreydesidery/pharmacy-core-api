package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current tenant's catalogue. */
public interface CatalogueService {

  List<CategoryView> categories();

  /** 409 duplicate_name when the tenant already has a category of that name, in any case. */
  CategoryView createCategory(CategoryCommand c);

  CategoryView changeCategory(UUID uid, CategoryCommand c);

  List<ManufacturerView> manufacturers();

  ManufacturerView createManufacturer(ManufacturerCommand c);

  ManufacturerView changeManufacturer(UUID uid, ManufacturerCommand c);

  /** Ordered by name, offset paging. */
  Page<ProductView> products(ProductFilter filter, PageQuery query);

  Optional<ProductView> product(UUID productUid);

  /** The product with this code, in any case. */
  Optional<ProductView> productByCode(String code);

  List<ProductView> products(Collection<UUID> productUids);

  /** Publishes product.changed; 409 duplicate_sku or duplicate_gtin. */
  ProductView createProduct(ProductCommand c);

  /** 409 stale_version when {@code version} is not the product's; publishes product.changed. */
  ProductView changeProduct(UUID uid, ProductCommand c, boolean active, long version);
}
