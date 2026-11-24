package tz.co.pharmacy.delivery.api.v1.exports;

import static tz.co.pharmacy.delivery.api.Spreadsheet.column;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.Spreadsheet;
import tz.co.pharmacy.delivery.api.Spreadsheet.Column;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductFilter;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductType;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.inventory.ports.inbound.ExpiringBatchView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockFilter;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockItemView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockStatus;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerType;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerView;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.modules.receivables.ports.inbound.AccountView;
import tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleFilter;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleStatus;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleSummaryView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * Exports of the lists, to Excel ({@code format=xlsx}, the default) or CSV, headed in English or
 * Swahili ({@code lang=sw}): every row the list's filters match, not only the page on screen. Each
 * takes the same filters and permission as its list. The product export's columns are the ones a
 * product import reads, so a pharmacy can export, edit and import back.
 */
@RestController
@RequestMapping("/api/v1")
public class ExportHandler {

  private final CatalogueService catalogue;
  private final StockQueries stock;
  private final SaleService sales;
  private final CustomerService customers;
  private final SupplierService suppliers;
  private final CustomerCredit credit;
  private final TenantCalendar calendar;

  public ExportHandler(
      CatalogueService catalogue,
      StockQueries stock,
      SaleService sales,
      CustomerService customers,
      SupplierService suppliers,
      CustomerCredit credit,
      TenantCalendar calendar) {
    this.catalogue = catalogue;
    this.stock = stock;
    this.sales = sales;
    this.customers = customers;
    this.suppliers = suppliers;
    this.credit = credit;
    this.calendar = calendar;
  }

  private static final List<Column<ProductView>> PRODUCTS =
      List.of(
          column("Code", "Msimbo", ProductView::sku),
          column("Name", "Jina", ProductView::name),
          column("Type", "Aina", ProductView::productType),
          column("Generic name", "Jina la kisayansi", ProductView::genericName),
          column("Brand", "Chapa", ProductView::brand),
          column("Barcode (GTIN)", "Msimbopau (GTIN)", ProductView::gtin),
          column("ATC code", "Msimbo wa ATC", ProductView::atcCode),
          column("Category", "Kundi", ProductView::categoryName),
          column("Manufacturer", "Mtengenezaji", ProductView::manufacturerName),
          column("Dosage form", "Umbo la dawa", ProductView::dosageForm),
          column("Strength", "Nguvu", ProductView::strength),
          column("Pack size", "Ukubwa wa pakiti", ProductView::packSize),
          column("Dispensing class", "Aina ya utoaji", ProductView::schedule),
          column("Unit", "Kipimo", ProductView::unitOfMeasure),
          column("Model", "Modeli", ProductView::model),
          column("Warranty (months)", "Dhamana (miezi)", ProductView::warrantyMonths),
          column("Cost", "Gharama", ProductView::costPrice),
          column("Price", "Bei", ProductView::sellingPrice),
          column("VAT %", "VAT %", ProductView::taxRatePercent),
          column("VAT category", "Aina ya VAT", ProductView::taxCategory),
          column("Reorder level", "Kiwango cha kuagiza", ProductView::reorderLevel),
          column("Storage", "Uhifadhi", ProductView::storage),
          column("Active", "Inatumika", ProductView::active));

  @GetMapping("/products/export")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ResponseEntity<byte[]> products(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID categoryUid,
      @RequestParam(required = false) Boolean active,
      @RequestParam(required = false) String productType,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String lang) {
    ProductFilter filter =
        new ProductFilter(
            q,
            categoryUid,
            active,
            blank(productType)
                ? null
                : Checks.oneOf(ProductType.class, productType, "productType"));
    return Spreadsheet.answer(
        "products",
        format,
        lang,
        calendar.zone(),
        PRODUCTS,
        Spreadsheet.all(page -> catalogue.products(filter, page)));
  }

  @GetMapping("/stock/export")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ResponseEntity<byte[]> stock(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID categoryUid,
      @RequestParam(required = false) String productType,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String lang) {
    StockFilter filter =
        new StockFilter(
            q,
            blank(status) ? null : Checks.oneOf(StockStatus.class, status, "status"),
            categoryUid,
            blank(productType) ? null : productType.trim().toUpperCase());
    List<Column<StockItemView>> columns =
        List.of(
            column("Code", "Msimbo", StockItemView::sku),
            column("Product", "Bidhaa", StockItemView::productName),
            column("Type", "Aina", StockItemView::productType),
            column("Generic name", "Jina la kisayansi", StockItemView::genericName),
            column("Category", "Kundi", StockItemView::categoryName),
            column("On hand", "Iliyopo", StockItemView::quantity),
            column("Reorder level", "Kiwango cha kuagiza", StockItemView::reorderLevel),
            column("Batches", "Bechi", StockItemView::batchCount),
            column("Nearest expiry", "Inaisha karibu", StockItemView::nearestExpiry),
            column("Price", "Bei", StockItemView::sellingPrice),
            column("Cost value", "Thamani kwa gharama", StockItemView::costValue),
            column("Status", "Hali", StockItemView::status));
    return Spreadsheet.answer(
        "stock",
        format,
        lang,
        calendar.zone(),
        columns,
        Spreadsheet.all(page -> stock.stock(filter, page)));
  }

  @GetMapping("/stock/expiring/export")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "inventory.view")
  public ResponseEntity<byte[]> expiring(
      @RequestParam(required = false) Integer withinDays,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String lang) {
    int days = withinDays == null ? 90 : withinDays;
    List<Column<ExpiringBatchView>> columns =
        List.of(
            column("Product", "Bidhaa", ExpiringBatchView::productName),
            column("Batch", "Bechi", ExpiringBatchView::batchNumber),
            column("Expiry", "Inaisha", ExpiringBatchView::expiryDate),
            column("Days left", "Siku zilizobaki", ExpiringBatchView::daysLeft),
            column("Quantity", "Idadi", ExpiringBatchView::quantity),
            column("Cost value", "Thamani kwa gharama", ExpiringBatchView::costValue));
    return Spreadsheet.answer(
        "expiring-batches",
        format,
        lang,
        calendar.zone(),
        columns,
        Spreadsheet.all(page -> stock.expiring(ExecutionContext.reportBranches(), days, page)));
  }

  @GetMapping("/sales/export")
  @Access(scope = AccessScope.BRANCH, permission = "sales.view")
  public ResponseEntity<byte[]> sales(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant since,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String lang) {
    SaleFilter filter =
        new SaleFilter(
            q,
            blank(status) ? null : Checks.oneOf(SaleStatus.class, status, "status"),
            from,
            to,
            since);
    List<Column<SaleSummaryView>> columns =
        List.of(
            column("Invoice", "Ankara", SaleSummaryView::number),
            column("Date", "Tarehe", SaleSummaryView::createdAt),
            column("Customer", "Mteja", SaleSummaryView::customerName),
            column("Cashier", "Mhudumu", SaleSummaryView::cashierName),
            column(
                "Payment",
                "Malipo",
                s -> String.join(", ", s.paymentMethods().stream().map(Object::toString).toList())),
            column("Items", "Bidhaa", SaleSummaryView::itemCount),
            column("Total", "Jumla", SaleSummaryView::total),
            column("Paid", "Kilicholipwa", SaleSummaryView::paidTotal),
            column("Status", "Hali", SaleSummaryView::status));
    return Spreadsheet.answer(
        "sales",
        format,
        lang,
        calendar.zone(),
        columns,
        Spreadsheet.all(page -> sales.list(filter, page)));
  }

  /**
   * Customers with their phones, emails and insurance numbers: people's data in bulk, so it needs
   * {@code data.export} besides the list's {@code partners.view} (ISO-10, Tanzania's PDPA).
   */
  @GetMapping("/customers/export")
  @Access(
      scope = AccessScope.TENANT,
      allOf = {"partners.view", "data.export"})
  public ResponseEntity<byte[]> customers(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String lang) {
    CustomerType customerType = blank(type) ? null : Checks.oneOf(CustomerType.class, type, "type");
    List<Column<CustomerView>> columns =
        List.of(
            column("Code", "Msimbo", CustomerView::code),
            column("Name", "Jina", CustomerView::name),
            column("Type", "Aina", CustomerView::type),
            column("Phone", "Simu", CustomerView::phone),
            column("Email", "Barua pepe", CustomerView::email),
            column("Address", "Anwani", CustomerView::address),
            column("Credit limit", "Kikomo cha mkopo", CustomerView::creditLimit),
            column("Payment terms", "Masharti ya malipo", CustomerView::paymentTerms),
            column("Insurer", "Bima", CustomerView::insuranceProvider),
            column("Insurance number", "Namba ya bima", CustomerView::insuranceNumber),
            column("Active", "Inatumika", CustomerView::active));
    return Spreadsheet.answer(
        "customers",
        format,
        lang,
        calendar.zone(),
        columns,
        Spreadsheet.all(page -> customers.search(q, customerType, page)));
  }

  @GetMapping("/suppliers/export")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ResponseEntity<byte[]> suppliers(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String lang) {
    List<Column<SupplierView>> columns =
        List.of(
            column("Code", "Msimbo", SupplierView::code),
            column("Name", "Jina", SupplierView::name),
            column("Contact person", "Mtu wa mawasiliano", SupplierView::contactPerson),
            column("Phone", "Simu", SupplierView::phone),
            column("Email", "Barua pepe", SupplierView::email),
            column("TIN", "TIN", SupplierView::tin),
            column("Address", "Anwani", SupplierView::address),
            column("Payment terms", "Masharti ya malipo", SupplierView::paymentTerms),
            column("Lead time (days)", "Muda wa kuleta (siku)", SupplierView::leadTimeDays),
            column("Active", "Inatumika", SupplierView::active));
    return Spreadsheet.answer(
        "suppliers",
        format,
        lang,
        calendar.zone(),
        columns,
        Spreadsheet.all(page -> suppliers.search(q, page)));
  }

  @GetMapping("/receivables/accounts/export")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ResponseEntity<byte[]> accounts(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean withBalance,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String lang) {
    List<Column<AccountView>> columns =
        List.of(
            column("Customer", "Mteja", AccountView::customerName),
            column("Credit limit", "Kikomo cha mkopo", AccountView::creditLimit),
            column("Balance", "Salio", AccountView::balance),
            column("Available", "Kinachopatikana", AccountView::available),
            column("Overdue", "Kilichopitwa na muda", AccountView::overdue),
            column("Oldest open", "Deni la zamani zaidi", AccountView::oldestOpenDate),
            column("Unallocated credit", "Mkopo usiogawiwa", AccountView::unallocatedCredit),
            column("Terms (days)", "Masharti (siku)", AccountView::termsDays),
            column("On hold", "Imesimamishwa", AccountView::onHold));
    return Spreadsheet.answer(
        "customer-accounts",
        format,
        lang,
        calendar.zone(),
        columns,
        Spreadsheet.all(page -> credit.accounts(q, Boolean.TRUE.equals(withBalance), page)));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
