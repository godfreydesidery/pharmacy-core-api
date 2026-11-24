package tz.co.pharmacy.delivery.api.v1.inventory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.HealthFields;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.delivery.api.Spreadsheet;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.sales.ports.inbound.PrescriptionDetails;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleFilter;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The controlled-drugs register of the current branch: each CONTROLLED product's balance, and per
 * product every stock movement with its running balance, the document it came from and, for sales
 * and returns, the prescription (patient, prescriber, registration number, reference).
 */
@RestController
@RequestMapping("/api/v1/controlled-drugs")
@RequiredArgsConstructor
public class ControlledDrugsHandler {

  private final CatalogueService catalogue;
  private final StockQueries stockQueries;
  private final SaleService sales;
  private final tz.co.pharmacy.platform.kernel.TenantCalendar calendar;
  private final tz.co.pharmacy.platform.persistence.audit.AuditLog audit;

  /**
   * A product of the register and its sellable balance at the branch; {@code schedule} is its class
   * now ({@code controlledNow} false for a product once CONTROLLED and since changed).
   */
  public record ControlledProduct(
      UUID productUid,
      String name,
      String strength,
      String dosageForm,
      int onHand,
      Schedule schedule,
      boolean controlledNow) {}

  /** One register line: in and out are the movement's units; balance is after it. */
  public record RegisterLine(
      UUID uid,
      Instant at,
      MovementType type,
      String reference,
      String batchNumber,
      int quantityIn,
      int quantityOut,
      int balance,
      String patientName,
      String prescriberName,
      String prescriberRegistration,
      String prescriptionReference,
      String note,
      String recordedBy,
      java.time.LocalDate prescriptionDate,
      /* The pharmacist who dispensed the sale (STD-7). */
      String dispensedBy,
      /* A sale's prescription the caller may not read (no health.view): its fields are null. */
      boolean withheld) {}

  /** The register's products by name, a page at a time (STD-7: no longer one capped list). */
  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<Page<ControlledProduct>> products(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    // FRD-6: every product CONTROLLED now or at any time before, so a class changed and changed
    // back never takes a product's movements out of the register.
    Page<ProductView> controlled = catalogue.controlledRegisterProducts(Pages.of(cursor, limit));
    Map<UUID, Integer> onHand =
        stockQueries.onHand(controlled.items().stream().map(ProductView::uid).toList());
    return ApiResponse.ok(
        controlled.map(
            p ->
                new ControlledProduct(
                    p.uid(),
                    p.name(),
                    p.strength(),
                    p.dosageForm() == null ? null : p.dosageForm().name(),
                    onHand.getOrDefault(p.uid(), 0),
                    p.schedule(),
                    p.schedule() == Schedule.CONTROLLED)),
        "Controlled products retrieved");
  }

  @GetMapping("/{productUid}/register")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<Page<RegisterLine>> register(
      @PathVariable UUID productUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    controlled(productUid);
    if (HealthFields.visible()) {
      // STD-5: the register names patients and prescribers; who read it is recorded.
      audit.record("CONTROLLED_REGISTER_VIEWED", "Product", productUid, null, Map.of());
    }
    return ApiResponse.ok(lines(productUid, Pages.of(cursor, limit)), "Register retrieved");
  }

  /**
   * The whole register of the product at this branch, to Excel or CSV, for the inspector. It names
   * every patient and prescription, so it needs {@code data.export} besides {@code inventory.view}
   * (ISO-10); the register on screen needs only the latter.
   */
  @GetMapping("/{productUid}/register/export")
  @Access(
      scope = AccessScope.BRANCH,
      allOf = {"inventory.view", Permissions.DATA_EXPORT})
  public ResponseEntity<byte[]> exportRegister(
      @PathVariable UUID productUid,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String lang) {
    ProductView product = controlled(productUid);
    audit.record(
        "CONTROLLED_REGISTER_EXPORTED",
        "Product",
        productUid,
        null,
        Map.of(
            "format", format == null ? "xlsx" : format, "healthDetails", HealthFields.visible()));
    List<Spreadsheet.Column<RegisterLine>> columns =
        List.of(
            Spreadsheet.column("Date", "Tarehe", RegisterLine::at),
            Spreadsheet.column("Entry", "Kiingilio", RegisterLine::type),
            Spreadsheet.column("Reference", "Rejea", RegisterLine::reference),
            Spreadsheet.column("Batch", "Bechi", RegisterLine::batchNumber),
            Spreadsheet.column("In", "Imeingia", RegisterLine::quantityIn),
            Spreadsheet.column("Out", "Imetoka", RegisterLine::quantityOut),
            Spreadsheet.column("Balance", "Salio", RegisterLine::balance),
            Spreadsheet.column("Patient", "Mgonjwa", RegisterLine::patientName),
            Spreadsheet.column("Prescriber", "Daktari", RegisterLine::prescriberName),
            Spreadsheet.column(
                "Registration", "Namba ya usajili", RegisterLine::prescriberRegistration),
            Spreadsheet.column(
                "Prescription", "Cheti cha daktari", RegisterLine::prescriptionReference),
            Spreadsheet.column(
                "Prescription date", "Tarehe ya cheti", RegisterLine::prescriptionDate),
            Spreadsheet.column("Dispensed by", "Ametolewa na", RegisterLine::dispensedBy),
            Spreadsheet.column("Note", "Maelezo", RegisterLine::note),
            Spreadsheet.column("By", "Na", RegisterLine::recordedBy));
    return Spreadsheet.answer(
        "controlled-register-" + product.name().replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(),
        format,
        lang,
        calendar.zone(),
        columns,
        Spreadsheet.all(page -> lines(productUid, page)));
  }

  /**
   * The product, which must be a controlled drug now or before: 404 product_not_found; 422
   * not_controlled.
   */
  private ProductView controlled(UUID productUid) {
    ProductView product =
        catalogue
            .product(productUid)
            .orElseThrow(() -> Refusal.notFound("product_not_found", "No such product."));
    if (product.schedule() != Schedule.CONTROLLED && !catalogue.everControlled(productUid)) {
      throw Refusal.invalid(
          "not_controlled", product.name() + " is not a controlled drug.", "productUid");
    }
    return product;
  }

  /** A page of the register: the movements with the prescription of each sale or return. */
  private Page<RegisterLine> lines(UUID productUid, PageQuery query) {
    Page<MovementView> movements = stockQueries.movements(productUid, null, query);
    Map<String, Optional<SaleView>> salesByReference = new HashMap<>();
    boolean visible = HealthFields.visible();
    return movements.map(
        m -> {
          SaleView sale =
              m.type() == MovementType.SALE || m.type() == MovementType.SALE_RETURN
                  ? salesByReference.computeIfAbsent(m.reference(), this::saleOf).orElse(null)
                  : null;
          PrescriptionDetails found = sale == null ? null : sale.prescription();
          boolean withheld = found != null && !visible;
          PrescriptionDetails rx = withheld ? null : found;
          return new RegisterLine(
              m.uid(),
              m.createdAt(),
              m.type(),
              m.reference(),
              m.batchNumber(),
              Math.max(m.quantityChange(), 0),
              Math.max(-m.quantityChange(), 0),
              m.balanceAfter(),
              rx == null ? null : rx.patientName(),
              rx == null ? null : rx.prescriberName(),
              rx == null ? null : rx.prescriberRegistration(),
              rx == null ? null : rx.reference(),
              m.note(),
              m.createdBy(),
              rx == null ? null : rx.prescriptionDate(),
              sale == null ? null : sale.dispensedBy(),
              withheld);
        });
  }

  /**
   * The prescription of the sale a movement points at: its number for a sale, or the sale number a
   * return names in its reference.
   */
  private Optional<SaleView> saleOf(String reference) {
    if (reference == null || reference.isBlank()) {
      return Optional.empty();
    }
    String number = reference.trim().split("\\s+")[0];
    // Its own lookup, never the page the register itself is on.
    return sales
        .list(new SaleFilter(number, null, null, null), PageQuery.of(null, 5))
        .items()
        .stream()
        .filter(s -> number.equals(s.number()))
        .findFirst()
        .flatMap(s -> sales.find(s.uid()));
  }
}
