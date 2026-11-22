package tz.co.pharmacy.delivery.api.v1.imports;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Imports from Excel (.xlsx) or CSV, for products, customers, suppliers, a branch's opening stock
 * and a price list's items. Each has a template ({@code …/import/template?lang=}), a check that
 * writes nothing ({@code POST …/import/check}) and the import itself ({@code POST …/import}), which
 * writes every row in one transaction or, when any row has a problem, nothing. Both answer an
 * {@link ImportReport}: the rows read, what they add and change, and every problem by row and
 * column. Each takes the permission of adding the record by hand.
 */
@RestController
@RequestMapping("/api/v1")
public class ImportHandler {

  private final ImportEngine engine;
  private final ProductImport products;
  private final CustomerImport customers;
  private final SupplierImport suppliers;
  private final OpeningStockImport openingStock;
  private final PriceListImport priceLists;

  ImportHandler(
      ImportEngine engine,
      ProductImport products,
      CustomerImport customers,
      SupplierImport suppliers,
      OpeningStockImport openingStock,
      PriceListImport priceLists) {
    this.engine = engine;
    this.products = products;
    this.customers = customers;
    this.suppliers = suppliers;
    this.openingStock = openingStock;
    this.priceLists = priceLists;
  }

  // products

  @GetMapping("/products/import/template")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ResponseEntity<byte[]> productTemplate(@RequestParam(required = false) String lang) {
    return ImportTemplate.answer(products, swahili(lang));
  }

  @PostMapping("/products/import/check")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<ImportReport> checkProducts(
      @RequestParam("file") MultipartFile file, @RequestParam(required = false) String lang) {
    return checked(engine.check(products, sheet(file), swahili(lang)));
  }

  @PostMapping("/products/import")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<ImportReport> importProducts(
      @RequestParam("file") MultipartFile file, @RequestParam(required = false) String lang) {
    return imported(engine.save(products, sheet(file), swahili(lang)));
  }

  // customers

  @GetMapping("/customers/import/template")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ResponseEntity<byte[]> customerTemplate(@RequestParam(required = false) String lang) {
    return ImportTemplate.answer(customers, swahili(lang));
  }

  @PostMapping("/customers/import/check")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<ImportReport> checkCustomers(
      @RequestParam("file") MultipartFile file, @RequestParam(required = false) String lang) {
    return checked(engine.check(customers, sheet(file), swahili(lang)));
  }

  @PostMapping("/customers/import")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<ImportReport> importCustomers(
      @RequestParam("file") MultipartFile file, @RequestParam(required = false) String lang) {
    return imported(engine.save(customers, sheet(file), swahili(lang)));
  }

  // suppliers

  @GetMapping("/suppliers/import/template")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ResponseEntity<byte[]> supplierTemplate(@RequestParam(required = false) String lang) {
    return ImportTemplate.answer(suppliers, swahili(lang));
  }

  @PostMapping("/suppliers/import/check")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<ImportReport> checkSuppliers(
      @RequestParam("file") MultipartFile file, @RequestParam(required = false) String lang) {
    return checked(engine.check(suppliers, sheet(file), swahili(lang)));
  }

  @PostMapping("/suppliers/import")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<ImportReport> importSuppliers(
      @RequestParam("file") MultipartFile file, @RequestParam(required = false) String lang) {
    return imported(engine.save(suppliers, sheet(file), swahili(lang)));
  }

  // opening stock, at the current branch

  @GetMapping("/stock/opening/import/template")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ResponseEntity<byte[]> openingStockTemplate(@RequestParam(required = false) String lang) {
    return ImportTemplate.answer(openingStock, swahili(lang));
  }

  @PostMapping("/stock/opening/import/check")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<ImportReport> checkOpeningStock(
      @RequestParam("file") MultipartFile file, @RequestParam(required = false) String lang) {
    return checked(engine.check(openingStock, sheet(file), swahili(lang)));
  }

  @PostMapping("/stock/opening/import")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<ImportReport> importOpeningStock(
      @RequestParam("file") MultipartFile file, @RequestParam(required = false) String lang) {
    return imported(engine.save(openingStock, sheet(file), swahili(lang)));
  }

  // a price list's items: re-priced or added by product Code and minimum quantity

  @GetMapping("/price-lists/{uid}/import/template")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ResponseEntity<byte[]> priceListTemplate(
      @PathVariable UUID uid,
      @RequestParam(required = false) String lang,
      @RequestParam(required = false) Boolean all) {
    return ImportTemplate.answer(priceLists.forList(uid, Boolean.TRUE.equals(all)), swahili(lang));
  }

  @PostMapping("/price-lists/{uid}/import/check")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<ImportReport> checkPriceList(
      @PathVariable UUID uid,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String lang) {
    return checked(engine.check(priceLists.forList(uid), sheet(file), swahili(lang)));
  }

  @PostMapping("/price-lists/{uid}/import")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<ImportReport> importPriceList(
      @PathVariable UUID uid,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String lang) {
    return imported(engine.save(priceLists.forList(uid), sheet(file), swahili(lang)));
  }

  private static SheetFile sheet(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw Refusal.invalid("empty_file", "Choose a file to import.", "file");
    }
    try {
      return SheetFile.read(file.getOriginalFilename(), file.getBytes());
    } catch (IOException failure) {
      throw Refusal.invalid("unreadable_file", "The file could not be read.", "file");
    }
  }

  private static ApiResponse<ImportReport> checked(ImportReport report) {
    return ApiResponse.ok(
        report,
        report.problemCount() == 0 ? "The file is ready to import" : "The file has problems");
  }

  private static ApiResponse<ImportReport> imported(ImportReport report) {
    return ApiResponse.ok(
        report, report.saved() ? "Imported" : "Nothing was imported: the file has problems");
  }

  private static boolean swahili(String lang) {
    return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("sw");
  }
}
