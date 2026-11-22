package tz.co.pharmacy.delivery.api.v1.catalogue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.CountsAgainst;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CategoryCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CategoryView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.DosageForm;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ManufacturerCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ManufacturerView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductFilter;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductType;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Storage;
import tz.co.pharmacy.modules.catalogue.ports.inbound.TrackingMode;
import tz.co.pharmacy.modules.catalogue.ports.inbound.UnitOfMeasure;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Categories, manufacturers, dosage forms and products of the tenant (contract §7). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CatalogueHandler {

  private final CatalogueService catalogue;

  // categories

  @GetMapping("/categories")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<?> categories() {
    return ApiResponse.ok(Pages.slice(catalogue.categories()), "Categories retrieved");
  }

  @PostMapping("/categories")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<CategoryView> createCategory(@RequestBody CategoryRequest body) {
    return ApiResponse.created(
        catalogue.createCategory(
            new CategoryCommand(body.name(), body.description(), body.parentUid())),
        "Category created");
  }

  @PutMapping("/categories/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<CategoryView> changeCategory(
      @PathVariable UUID uid, @RequestBody CategoryRequest body) {
    return ApiResponse.ok(
        catalogue.changeCategory(
            uid, new CategoryCommand(body.name(), body.description(), body.parentUid())),
        "Category changed");
  }

  // manufacturers

  @GetMapping("/manufacturers")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<?> manufacturers() {
    return ApiResponse.ok(Pages.slice(catalogue.manufacturers()), "Manufacturers retrieved");
  }

  @PostMapping("/manufacturers")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<ManufacturerView> createManufacturer(@RequestBody ManufacturerRequest body) {
    return ApiResponse.created(
        catalogue.createManufacturer(new ManufacturerCommand(body.name(), body.country())),
        "Manufacturer created");
  }

  @PutMapping("/manufacturers/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<ManufacturerView> changeManufacturer(
      @PathVariable UUID uid, @RequestBody ManufacturerRequest body) {
    return ApiResponse.ok(
        catalogue.changeManufacturer(uid, new ManufacturerCommand(body.name(), body.country())),
        "Manufacturer changed");
  }

  // product types, tracking modes and units

  /** The product types, each with the tracking mode a product of that type gets by default. */
  @GetMapping("/product-types")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<ProductTypeOption>> productTypes() {
    return ApiResponse.ok(
        Arrays.stream(ProductType.values())
            .map(type -> new ProductTypeOption(type.name(), type.defaultTracking().name()))
            .toList(),
        "Product types retrieved");
  }

  @GetMapping("/tracking-modes")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<String>> trackingModes() {
    return ApiResponse.ok(
        Arrays.stream(TrackingMode.values()).map(Enum::name).toList(), "Tracking modes retrieved");
  }

  @GetMapping("/units-of-measure")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<String>> unitsOfMeasure() {
    return ApiResponse.ok(
        Arrays.stream(UnitOfMeasure.values()).map(Enum::name).toList(),
        "Units of measure retrieved");
  }

  /** A product type and the tracking mode it defaults to, such as MEDICAL_EQUIPMENT and SERIAL. */
  public record ProductTypeOption(String code, String defaultTrackingMode) {}

  // dosage forms

  @GetMapping("/dosage-forms")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<String>> dosageForms() {
    return ApiResponse.ok(
        Arrays.stream(DosageForm.values()).map(Enum::name).toList(), "Dosage forms retrieved");
  }

  // products

  @GetMapping("/products")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<Page<ProductView>> products(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID categoryUid,
      @RequestParam(required = false) Boolean active,
      @RequestParam(required = false) String productType,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        catalogue.products(
            new ProductFilter(
                q,
                categoryUid,
                active,
                productType == null || productType.isBlank()
                    ? null
                    : Checks.oneOf(ProductType.class, productType, "productType")),
            Pages.of(cursor, limit)),
        "Products retrieved");
  }

  @CountsAgainst(PlanLimit.PRODUCTS)
  @PostMapping("/products")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<ProductView> createProduct(@RequestBody ProductRequest body) {
    return ApiResponse.created(catalogue.createProduct(body.toCommand()), "Product created");
  }

  @GetMapping("/products/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<ProductView> product(@PathVariable UUID uid) {
    return ApiResponse.ok(
        catalogue
            .product(uid)
            .orElseThrow(() -> Refusal.notFound("product_not_found", "No such product.")),
        "Product retrieved");
  }

  @PutMapping("/products/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<ProductView> changeProduct(
      @PathVariable UUID uid, @RequestBody ProductRequest body) {
    long version = Checks.required(body.version(), "version");
    boolean active = body.active() == null || body.active();
    return ApiResponse.ok(
        catalogue.changeProduct(uid, body.toCommand(), active, version), "Product changed");
  }

  /** {@code parentUid} null for a top-level category. */
  public record CategoryRequest(String name, String description, UUID parentUid) {}

  public record ManufacturerRequest(String name, String country) {}

  /** The contract's ProductInput; {@code active} and {@code version} only on a change. */
  public record ProductRequest(
      String name,
      String genericName,
      String sku,
      String gtin,
      String atcCode,
      UUID categoryUid,
      UUID manufacturerUid,
      String dosageForm,
      String strength,
      String packSize,
      String schedule,
      BigDecimal sellingPrice,
      BigDecimal costPrice,
      BigDecimal taxRatePercent,
      Integer reorderLevel,
      String storage,
      Boolean active,
      Long version,
      String productType,
      String trackingMode,
      String unitOfMeasure,
      String brand,
      String model,
      Integer warrantyMonths,
      Boolean requiresSerialAtSale) {

    ProductCommand toCommand() {
      return new ProductCommand(
          name,
          genericName,
          sku,
          gtin,
          atcCode,
          categoryUid,
          manufacturerUid,
          blank(dosageForm) ? null : Checks.oneOf(DosageForm.class, dosageForm, "dosageForm"),
          strength,
          packSize,
          blank(schedule) ? null : Checks.oneOf(Schedule.class, schedule, "schedule"),
          sellingPrice,
          costPrice,
          taxRatePercent,
          reorderLevel,
          blank(storage) ? null : Checks.oneOf(Storage.class, storage, "storage"),
          blank(productType) ? null : Checks.oneOf(ProductType.class, productType, "productType"),
          blank(trackingMode)
              ? null
              : Checks.oneOf(TrackingMode.class, trackingMode, "trackingMode"),
          blank(unitOfMeasure)
              ? null
              : Checks.oneOf(UnitOfMeasure.class, unitOfMeasure, "unitOfMeasure"),
          brand,
          model,
          warrantyMonths,
          requiresSerialAtSale);
    }

    private static boolean blank(String value) {
      return value == null || value.isBlank();
    }
  }
}
