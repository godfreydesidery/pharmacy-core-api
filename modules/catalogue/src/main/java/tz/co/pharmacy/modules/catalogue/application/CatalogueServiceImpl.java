package tz.co.pharmacy.modules.catalogue.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.catalogue.domain.Category;
import tz.co.pharmacy.modules.catalogue.domain.Manufacturer;
import tz.co.pharmacy.modules.catalogue.domain.Product;
import tz.co.pharmacy.modules.catalogue.events.ProductChanged;
import tz.co.pharmacy.modules.catalogue.persistence.CategoryRepository;
import tz.co.pharmacy.modules.catalogue.persistence.ManufacturerRepository;
import tz.co.pharmacy.modules.catalogue.persistence.ProductRepository;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CategoryCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CategoryView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ManufacturerCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ManufacturerView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductFilter;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Storage;
import tz.co.pharmacy.modules.catalogue.ports.outbound.ProductStock;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
@RequiredArgsConstructor
class CatalogueServiceImpl implements CatalogueService {

  private static final Pattern GTIN = Pattern.compile("^\\d{8,14}$");
  private static final Pattern ATC = Pattern.compile("^[A-Z][0-9]{2}[A-Z]{0,2}[0-9]{0,2}$");

  private final CategoryRepository categories;
  private final ManufacturerRepository manufacturers;
  private final ProductRepository products;
  private final DomainEventPublisher events;
  private final ProductStock stock;

  @PersistenceContext private EntityManager entityManager;

  /**
   * Every category, ordered by path. A tenant without categories gets the default tree on its first
   * read, so a new pharmacy starts with medicines, equipment, supplies and the rest.
   */
  @Override
  @Transactional
  public List<CategoryView> categories() {
    List<Category> all = categories.findAllByOrderByNameAsc();
    if (all.isEmpty()) {
      DefaultCategories.TREE.forEach((top, children) -> seed(top, children));
      all = categories.findAllByOrderByNameAsc();
    }
    return all.stream()
        .map(CatalogueServiceImpl::view)
        .sorted(Comparator.comparing(view -> view.path().toLowerCase()))
        .toList();
  }

  private void seed(String name, List<String> children) {
    Category top = categories.save(new Category(name, null, null));
    children.forEach(child -> categories.save(new Category(child, null, top)));
  }

  @Override
  @Transactional
  public CategoryView createCategory(CategoryCommand c) {
    String name = Checks.text(c.name(), "name", 200);
    Category parent = c.parentUid() == null ? null : parentCategory(c.parentUid(), null);
    requireUniqueAmongSiblings(name, parent, null);
    return view(
        categories.save(
            new Category(name, Checks.optionalText(c.description(), "description", 1000), parent)));
  }

  @Override
  @Transactional
  public CategoryView changeCategory(UUID uid, CategoryCommand c) {
    Category category = category(uid);
    String name = Checks.text(c.name(), "name", 200);
    Category parent = c.parentUid() == null ? null : parentCategory(c.parentUid(), category);
    requireUniqueAmongSiblings(name, parent, uid);
    category.change(name, Checks.optionalText(c.description(), "description", 1000), parent);
    return view(category);
  }

  /**
   * The parent of a new or moved category: it must exist, may not be the category itself or one of
   * its descendants, and the tree may not grow deeper than three levels.
   */
  private Category parentCategory(UUID parentUid, Category moving) {
    Category parent =
        categories
            .findByUid(parentUid)
            .orElseThrow(
                () -> Refusal.invalid("category_not_found", "No such category.", "parentUid"));
    if (moving != null && parent.isSelfOrAncestor(moving)) {
      throw Refusal.invalid(
          "category_cycle",
          "A category cannot sit under itself or one of its own sub-categories.",
          "parentUid");
    }
    int subtreeHeight = moving == null ? 1 : height(moving);
    if (parent.depth() + subtreeHeight > DefaultCategories.MAX_DEPTH) {
      throw Refusal.invalid(
          "category_too_deep",
          "Categories nest at most " + DefaultCategories.MAX_DEPTH + " levels deep.",
          "parentUid");
    }
    return parent;
  }

  /** 1 for a category without sub-categories. */
  private int height(Category category) {
    int height = 1;
    for (Category other : categories.findAllByOrderByNameAsc()) {
      if (!other.getUid().equals(category.getUid()) && other.isSelfOrAncestor(category)) {
        height = Math.max(height, other.depth() - category.depth() + 1);
      }
    }
    return height;
  }

  private void requireUniqueAmongSiblings(String name, Category parent, UUID exceptUid) {
    boolean taken =
        categories.findAllByOrderByNameAsc().stream()
            .filter(other -> exceptUid == null || !other.getUid().equals(exceptUid))
            .filter(other -> other.getName().equalsIgnoreCase(name))
            .anyMatch(
                other ->
                    parent == null
                        ? other.getParent() == null
                        : other.getParent() != null
                            && other.getParent().getUid().equals(parent.getUid()));
    if (taken) {
      throw duplicateName("category", name);
    }
  }

  /** The uids of the category and of every category below it. */
  private Set<UUID> withDescendants(UUID categoryUid) {
    Set<UUID> uids = new HashSet<>();
    uids.add(categoryUid);
    for (Category category : categories.findAllByOrderByNameAsc()) {
      for (Category up = category; up != null; up = up.getParent()) {
        if (up.getUid().equals(categoryUid)) {
          uids.add(category.getUid());
          break;
        }
      }
    }
    return uids;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ManufacturerView> manufacturers() {
    return manufacturers.findAllByOrderByNameAsc().stream()
        .map(CatalogueServiceImpl::view)
        .toList();
  }

  @Override
  @Transactional
  public ManufacturerView createManufacturer(ManufacturerCommand c) {
    String name = Checks.text(c.name(), "name", 200);
    if (manufacturers.existsByNameIgnoreCase(name)) {
      throw duplicateName("manufacturer", name);
    }
    return view(
        manufacturers.save(
            new Manufacturer(name, Checks.optionalText(c.country(), "country", 100))));
  }

  @Override
  @Transactional
  public ManufacturerView changeManufacturer(UUID uid, ManufacturerCommand c) {
    Manufacturer manufacturer = manufacturer(uid);
    String name = Checks.text(c.name(), "name", 200);
    if (manufacturers.existsByNameIgnoreCaseAndUidNot(name, uid)) {
      throw duplicateName("manufacturer", name);
    }
    manufacturer.change(name, Checks.optionalText(c.country(), "country", 100));
    return view(manufacturer);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ProductView> products(ProductFilter filter, PageQuery query) {
    ProductFilter f = filter == null ? new ProductFilter(null, null, null, null) : filter;
    Specification<Product> spec =
        Specs.<Product>containsAny(f.q(), "name", "genericName", "sku", "gtin", "brand", "model")
            .and(
                f.categoryUid() == null
                    ? Specs.<Product>all()
                    : Specs.<Product>in("category.uid", withDescendants(f.categoryUid())))
            .and(Specs.equal("active", f.active()))
            .and(Specs.equal("productType", f.productType()))
            .and(Specs.equal("schedule", f.schedule()));
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<Product> cq = cb.createQuery(Product.class);
    Root<Product> root = cq.from(Product.class);
    root.fetch("category", JoinType.LEFT);
    root.fetch("manufacturer", JoinType.LEFT);
    Predicate predicate = spec.toPredicate(root, cq, cb);
    if (predicate != null) {
      cq.where(predicate);
    }
    cq.select(root).orderBy(cb.asc(cb.lower(root.get("name"))), cb.asc(root.get("id")));
    List<Product> rows =
        entityManager
            .createQuery(cq)
            .setFirstResult(query.offset())
            .setMaxResults(query.limit() + 1)
            .getResultList();
    return Page.offset(rows, query).map(CatalogueServiceImpl::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProductView> product(UUID productUid) {
    return products.findByUid(productUid).map(CatalogueServiceImpl::view);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProductView> products(Collection<UUID> productUids) {
    if (productUids == null || productUids.isEmpty()) {
      return List.of();
    }
    return products.findByUidIn(productUids).stream().map(CatalogueServiceImpl::view).toList();
  }

  @Override
  @Transactional
  public ProductView createProduct(ProductCommand c) {
    Product.Details details = details(c, null);
    Product product = products.save(new Product(details));
    publish(product);
    return view(product);
  }

  @Override
  @Transactional
  public ProductView changeProduct(UUID uid, ProductCommand c, boolean active, long version) {
    Product product =
        products
            .findByUid(uid)
            .orElseThrow(() -> Refusal.notFound("product_not_found", "No such product."));
    if (product.getVersion() != version) {
      throw Refusal.conflict(
          "stale_version", "The product was changed by someone else; reload it and try again.");
    }
    Product.Details details = details(c, uid);
    if (details.trackingMode() != product.getTrackingMode()) {
      long held = stock.unitsHeld(uid);
      if (held > 0) {
        // Batches, serial units and plain quantities are stored differently: switching with
        // stock on hand would strand it. Sell, adjust out or transfer it first.
        throw Refusal.invalid(
            "tracking_mode_locked",
            "The tracking mode cannot change while "
                + held
                + " units are held in stock; bring the stock to zero first.",
            "trackingMode");
      }
    }
    product.change(details);
    product.activate(active);
    products.flush();
    publish(product);
    return view(product);
  }

  private Product.Details details(ProductCommand c, UUID existingUid) {
    String name = Checks.text(c.name(), "name", 200);
    String sku = Checks.optionalText(c.sku(), "sku", 64);
    String gtin = Checks.optionalText(c.gtin(), "gtin", 14);
    if (gtin != null && !GTIN.matcher(gtin).matches()) {
      throw Refusal.invalid("invalid_gtin", "gtin must be 8 to 14 digits (GS1 GTIN).", "gtin");
    }
    String atc = Checks.optionalText(c.atcCode(), "atcCode", 7);
    if (atc != null) {
      atc = atc.toUpperCase();
      if (!ATC.matcher(atc).matches()) {
        throw Refusal.invalid(
            "invalid_atc_code", "atcCode is not a WHO ATC code such as N02BE01.", "atcCode");
      }
    }
    if (sku != null
        && (existingUid == null
            ? products.existsBySkuIgnoreCase(sku)
            : products.existsBySkuIgnoreCaseAndUidNot(sku, existingUid))) {
      throw Refusal.conflict("duplicate_sku", "Another product already has SKU " + sku + ".");
    }
    if (gtin != null
        && (existingUid == null
            ? products.existsByGtin(gtin)
            : products.existsByGtinAndUidNot(gtin, existingUid))) {
      throw Refusal.conflict("duplicate_gtin", "Another product already has GTIN " + gtin + ".");
    }
    Category category = c.categoryUid() == null ? null : categoryForProduct(c.categoryUid());
    Manufacturer manufacturer =
        c.manufacturerUid() == null ? null : manufacturerForProduct(c.manufacturerUid());
    Integer reorder = c.reorderLevel() == null ? 0 : c.reorderLevel();
    if (reorder < 0) {
      throw Refusal.invalid(
          "invalid_reorder_level", "reorderLevel must be zero or more.", "reorderLevel");
    }
    ProductRules.Kind kind = ProductRules.kind(c);
    return new Product.Details(
        name,
        kind.genericName(),
        sku,
        gtin,
        kind.medicine() ? atc : null,
        category,
        manufacturer,
        kind.dosageForm(),
        Checks.optionalText(c.strength(), "strength", 100),
        Checks.optionalText(c.packSize(), "packSize", 100),
        kind.schedule(),
        Checks.notNegative(c.sellingPrice(), "sellingPrice"),
        Checks.notNegative(c.costPrice(), "costPrice"),
        Checks.percent(c.taxRatePercent(), "taxRatePercent"),
        reorder,
        c.storage() == null ? Storage.ROOM_TEMPERATURE : c.storage(),
        kind.productType(),
        kind.trackingMode(),
        kind.unitOfMeasure(),
        kind.brand(),
        kind.model(),
        kind.warrantyMonths(),
        kind.requiresSerialAtSale());
  }

  private void publish(Product product) {
    events.publish(
        "product.changed",
        meta ->
            new ProductChanged(
                meta,
                product.getUid(),
                product.getName(),
                product.getGenericName(),
                product.getSku(),
                product.getDosageForm() == null ? null : product.getDosageForm().name(),
                product.getStrength(),
                product.getCategory() == null ? null : product.getCategory().getUid(),
                product.getCategory() == null ? null : product.getCategory().getName(),
                product.getReorderLevel(),
                product.getSellingPrice(),
                product.isActive(),
                product.getProductType().name(),
                product.getTrackingMode().name(),
                product.getUnitOfMeasure().name()));
  }

  private Category category(UUID uid) {
    return categories
        .findByUid(uid)
        .orElseThrow(() -> Refusal.notFound("category_not_found", "No such category."));
  }

  private Manufacturer manufacturer(UUID uid) {
    return manufacturers
        .findByUid(uid)
        .orElseThrow(() -> Refusal.notFound("manufacturer_not_found", "No such manufacturer."));
  }

  private Category categoryForProduct(UUID uid) {
    return categories
        .findByUid(uid)
        .orElseThrow(
            () -> Refusal.invalid("category_not_found", "No such category.", "categoryUid"));
  }

  private Manufacturer manufacturerForProduct(UUID uid) {
    return manufacturers
        .findByUid(uid)
        .orElseThrow(
            () ->
                Refusal.invalid(
                    "manufacturer_not_found", "No such manufacturer.", "manufacturerUid"));
  }

  private static Refusal duplicateName(String what, String name) {
    Refusal refusal =
        Refusal.conflict("duplicate_name", "A " + what + " named " + name + " already exists.");
    return refusal.about("name");
  }

  private static CategoryView view(Category c) {
    return new CategoryView(
        c.getUid(),
        c.getName(),
        c.getDescription(),
        c.getCreatedAt(),
        c.getParent() == null ? null : c.getParent().getUid(),
        c.path());
  }

  private static ManufacturerView view(Manufacturer m) {
    return new ManufacturerView(m.getUid(), m.getName(), m.getCountry(), m.getCreatedAt());
  }

  private static ProductView view(Product p) {
    Category category = p.getCategory();
    Manufacturer manufacturer = p.getManufacturer();
    return new ProductView(
        p.getUid(),
        p.getName(),
        p.getGenericName(),
        p.getSku(),
        p.getGtin(),
        p.getAtcCode(),
        category == null ? null : category.getUid(),
        category == null ? null : category.getName(),
        manufacturer == null ? null : manufacturer.getUid(),
        manufacturer == null ? null : manufacturer.getName(),
        p.getDosageForm(),
        p.getStrength(),
        p.getPackSize(),
        p.getSchedule(),
        p.getSellingPrice(),
        p.getCostPrice(),
        p.getTaxRatePercent() == null ? BigDecimal.ZERO : p.getTaxRatePercent(),
        p.getReorderLevel(),
        p.getStorage(),
        p.isActive(),
        p.getVersion(),
        p.getCreatedAt(),
        p.getProductType(),
        p.getTrackingMode(),
        p.getUnitOfMeasure(),
        p.getBrand(),
        p.getModel(),
        p.getWarrantyMonths(),
        p.isRequiresSerialAtSale());
  }
}
