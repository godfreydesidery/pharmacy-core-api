package tz.co.pharmacy.delivery.api.v1.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.HealthFields;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdverseReactions;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdverseReactions.AdverseReactionView;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.platform.kernel.CellText;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Adverse drug reaction reports (pharmacovigilance, adversarial review STD-8). Reporting needs
 * {@code adr.report}; reading, since a report is health data, {@code health.view}; the CSV for TMDA
 * both. The CSV follows the sections of TMDA's yellow form ("Section,Field,Value"): verify the form
 * and its fields with TMDA before sending one.
 */
@RestController
@RequestMapping("/api/v1/adverse-reactions")
@RequiredArgsConstructor
public class AdverseReactionHandler {

  static final String REPORT = "adr.report";

  private final AdverseReactions reactions;
  private final CatalogueService catalogue;
  private final CustomerService customers;
  private final AuditLog audit;

  @GetMapping
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = HealthFields.PERMISSION)
  public ApiResponse<Page<AdverseReactionView>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        reactions.list(q, ExecutionContext.reportBranches(), Pages.of(cursor, limit)),
        "Reports retrieved");
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = REPORT)
  public ApiResponse<AdverseReactionView> report(@RequestBody ReportRequest body) {
    if (body == null) {
      throw Refusal.malformed("A report is required.");
    }
    UUID productUid = Checks.required(body.productUid(), "productUid");
    ProductView product =
        catalogue
            .product(productUid)
            .orElseThrow(
                () -> Refusal.invalid("product_not_found", "No such product.", "productUid"));
    if (body.customerUid() != null && customers.find(body.customerUid()).isEmpty()) {
      throw Refusal.invalid("customer_not_found", "No such customer.", "customerUid");
    }
    return ApiResponse.created(
        reactions.report(
            new AdverseReactions.ReportCommand(
                body.customerUid(),
                body.patientName(),
                body.patientAge(),
                blank(body.patientSex())
                    ? null
                    : Checks.oneOf(AdverseReactions.Sex.class, body.patientSex(), "patientSex"),
                body.patientWeightKg(),
                productUid,
                product.name(),
                body.batchNumber(),
                body.dose(),
                body.route(),
                body.indication(),
                body.therapyStarted(),
                body.therapyStopped(),
                body.reaction(),
                body.onsetDate(),
                Checks.oneOf(AdverseReactions.Seriousness.class, body.seriousness(), "seriousness"),
                blank(body.seriousReason())
                    ? null
                    : Checks.oneOf(
                        AdverseReactions.SeriousReason.class,
                        body.seriousReason(),
                        "seriousReason"),
                Checks.oneOf(AdverseReactions.Outcome.class, body.outcome(), "outcome"),
                body.reporterName(),
                body.reporterProfession(),
                body.reporterPhone(),
                body.note())),
        "Report recorded");
  }

  @GetMapping("/{uid}")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = HealthFields.PERMISSION)
  public ApiResponse<AdverseReactionView> report(@PathVariable UUID uid) {
    AdverseReactionView report = find(uid);
    audit.record("ADR_VIEWED", "AdverseReaction", uid, null, Map.of("number", report.number()));
    return ApiResponse.ok(report, "Report retrieved");
  }

  /** One report as CSV, in the order of TMDA's yellow form; audited (ADR_EXPORTED). */
  @GetMapping("/{uid}/export")
  @Access(
      scope = AccessScope.TENANT_OPTIONAL_BRANCH,
      allOf = {HealthFields.PERMISSION, REPORT})
  public ResponseEntity<byte[]> export(@PathVariable UUID uid) {
    AdverseReactionView r = find(uid);
    audit.record("ADR_EXPORTED", "AdverseReaction", uid, null, Map.of("number", r.number()));
    tz.co.pharmacy.delivery.edge.ExportAudit.recorded();
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[] {"Section", "Field", "Value"});
    String patient = "A. Patient information";
    rows.add(row(patient, "Report number", r.number()));
    rows.add(row(patient, "Patient name or initials", r.patientName()));
    rows.add(row(patient, "Age (years)", r.patientAge()));
    rows.add(row(patient, "Sex", r.patientSex()));
    rows.add(row(patient, "Weight (kg)", r.patientWeightKg()));
    String reaction = "B. Adverse reaction";
    rows.add(row(reaction, "Description of the reaction", r.reaction()));
    rows.add(row(reaction, "Date of onset", r.onsetDate()));
    rows.add(row(reaction, "Seriousness", r.seriousness()));
    rows.add(row(reaction, "Reason for seriousness", r.seriousReason()));
    rows.add(row(reaction, "Outcome", r.outcome()));
    String medicine = "C. Suspected medicine";
    rows.add(row(medicine, "Name (brand / generic)", r.productName()));
    rows.add(row(medicine, "Batch number", r.batchNumber()));
    rows.add(row(medicine, "Dose and frequency", r.dose()));
    rows.add(row(medicine, "Route", r.route()));
    rows.add(row(medicine, "Indication (reason for use)", r.indication()));
    rows.add(row(medicine, "Date started", r.therapyStarted()));
    rows.add(row(medicine, "Date stopped", r.therapyStopped()));
    String reporter = "D. Reporter";
    rows.add(row(reporter, "Name", r.reporterName()));
    rows.add(row(reporter, "Profession", r.reporterProfession()));
    rows.add(row(reporter, "Phone", r.reporterPhone()));
    rows.add(row(reporter, "Date of report", r.createdAt()));
    rows.add(row(reporter, "Other relevant information", r.note()));
    StringBuilder csv = new StringBuilder();
    for (String[] cells : rows) {
      for (int i = 0; i < cells.length; i++) {
        if (i > 0) {
          csv.append(',');
        }
        String cell = cells[i] == null ? "" : CellText.neutralised(cells[i]);
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
          cell = "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        csv.append(cell);
      }
      csv.append("\r\n");
    }
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + r.number() + "-tmda-adr.csv\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(csv.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** A report of a branch the caller may not use is not found (ADR 0003). */
  private AdverseReactionView find(UUID uid) {
    return reactions
        .find(uid)
        .filter(r -> ExecutionContext.mayUseBranch(r.branchUid()))
        .orElseThrow(
            () ->
                Refusal.notFound("adverse_reaction_not_found", "No such adverse reaction report."));
  }

  private static String[] row(String section, String field, Object value) {
    return new String[] {section, field, value == null ? null : value.toString()};
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record ReportRequest(
      UUID customerUid,
      String patientName,
      Integer patientAge,
      String patientSex,
      BigDecimal patientWeightKg,
      UUID productUid,
      String batchNumber,
      String dose,
      String route,
      String indication,
      LocalDate therapyStarted,
      LocalDate therapyStopped,
      String reaction,
      LocalDate onsetDate,
      String seriousness,
      String seriousReason,
      String outcome,
      String reporterName,
      String reporterProfession,
      String reporterPhone,
      String note) {}
}
