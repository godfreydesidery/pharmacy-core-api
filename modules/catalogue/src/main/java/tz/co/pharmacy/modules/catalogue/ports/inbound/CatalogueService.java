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

  /**
   * 409 stale_version when {@code version} is not the product's; publishes product.changed. A
   * change of its dispensing class (schedule) needs a manager's approval (403 approval_required,
   * SCHEDULE_CHANGE; one approval covers a whole import) and is audited (SCHEDULE_CHANGED, before
   * and after).
   */
  ProductView changeProduct(UUID uid, ProductCommand c, boolean active, long version);

  /**
   * The products of the controlled-drugs register, by name: every product CONTROLLED now or at any
   * time before (FRD-6).
   */
  List<ProductView> controlledRegisterProducts();

  /** Whether the product is CONTROLLED now or ever was. */
  boolean everControlled(UUID productUid);

  /**
   * Sells every STANDARD product of the pharmacy at its new standard VAT rate (STD-2): the rate
   * {@link ProductView#taxRatePercent()} shows and checkout charges. Returns how many changed.
   */
  int applyStandardVatRate(java.math.BigDecimal ratePercent);
}
