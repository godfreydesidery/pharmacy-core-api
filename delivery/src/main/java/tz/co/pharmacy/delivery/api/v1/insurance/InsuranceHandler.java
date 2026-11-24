package tz.co.pharmacy.delivery.api.v1.insurance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.application.checkout.ports.inbound.Checkout;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutLine;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.HealthFields;
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.delivery.api.RequiresFeature;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.insurance.ports.inbound.AdjudicationCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimBatchView;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimLineView;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimStatus;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimTotals;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimView;
import tz.co.pharmacy.modules.insurance.ports.inbound.CopayType;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageQuote;
import tz.co.pharmacy.modules.insurance.ports.inbound.Insurance;
import tz.co.pharmacy.modules.insurance.ports.inbound.MemberCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.MemberView;
import tz.co.pharmacy.modules.insurance.ports.inbound.PayerCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.PayerView;
import tz.co.pharmacy.modules.insurance.ports.inbound.RejectedTo;
import tz.co.pharmacy.modules.insurance.ports.inbound.RemittanceCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.RemittanceView;
import tz.co.pharmacy.modules.insurance.ports.inbound.SchemeCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.SchemeView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanFeature;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Insurance: payers, schemes and members (partners.*), claims, batches and remittances (finance.*),
 * and the POS coverage quote (sales.sell).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InsuranceHandler {

  private final Insurance insurance;
  private final Checkout checkout;
  private final AuditLog audit;

  // ---------------------------------------------------------------- payers and schemes

  @RequiresFeature(PlanFeature.INSURANCE)
  @GetMapping("/insurance/payers")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ApiResponse<List<PayerView>> payers() {
    return ApiResponse.ok(insurance.payers(), "Payers retrieved");
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @PostMapping("/insurance/payers")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<PayerView> createPayer(@RequestBody PayerCommand body) {
    return ApiResponse.created(insurance.createPayer(body), "Payer created");
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @PutMapping("/insurance/payers/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<PayerView> changePayer(
      @PathVariable UUID uid, @RequestBody PayerCommand body) {
    return ApiResponse.ok(insurance.changePayer(uid, body), "Payer changed");
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @GetMapping("/insurance/schemes")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ApiResponse<List<SchemeView>> schemes(@RequestParam(required = false) UUID payerUid) {
    return ApiResponse.ok(insurance.schemes(payerUid), "Schemes retrieved");
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @PostMapping("/insurance/schemes")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<SchemeView> createScheme(@RequestBody SchemeRequest body) {
    return ApiResponse.created(insurance.createScheme(body.command()), "Scheme created");
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @PutMapping("/insurance/schemes/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<SchemeView> changeScheme(
      @PathVariable UUID uid, @RequestBody SchemeRequest body) {
    return ApiResponse.ok(insurance.changeScheme(uid, body.command()), "Scheme changed");
  }

  // ---------------------------------------------------------------- members

  /** Members by number or name; {@code ?customerUid=} the customer's memberships. */
  @RequiresFeature(PlanFeature.INSURANCE)
  @GetMapping("/insurance/members")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ApiResponse<Page<MemberView>> members(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID customerUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    if (customerUid != null) {
      return ApiResponse.ok(Page.of(insurance.membersOf(customerUid), null), "Members retrieved");
    }
    return ApiResponse.ok(insurance.members(q, Pages.of(cursor, limit)), "Members retrieved");
  }

  /**
   * The customer's membership valid today, for the POS; {@code data} is null when there is none.
   * Most customers are not insured, so "none" is an ordinary answer, not a 404. Needs
   * partners.view, as the list of members does (it needed no permission before, ISO-10).
   */
  @GetMapping("/insurance/members/current")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ApiResponse<MemberView> currentMember(@RequestParam UUID customerUid) {
    return insurance
        .memberOf(customerUid, null)
        .map(member -> ApiResponse.ok(member, "Member retrieved"))
        .orElseGet(() -> ApiResponse.ok(null, "No valid insurance today"));
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @PostMapping("/insurance/members")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<MemberView> enrol(@RequestBody MemberCommand body) {
    return ApiResponse.created(insurance.enrol(body), "Member enrolled");
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @PutMapping("/insurance/members/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<MemberView> changeMember(
      @PathVariable UUID uid, @RequestBody MemberCommand body) {
    return ApiResponse.ok(insurance.changeMember(uid, body), "Member changed");
  }

  // ---------------------------------------------------------------- POS

  /** The split of a basket between the member's insurer and the patient. */
  @RequiresFeature(PlanFeature.INSURANCE)
  @PostMapping("/pos/coverage-quote")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<CoverageQuote> coverageQuote(@RequestBody QuoteRequest body) {
    List<CheckoutLine> lines =
        Checks.notEmpty(body.lines(), "lines").stream()
            .map(
                line ->
                    new CheckoutLine(
                        line.productUid(),
                        line.quantity() == null ? 0 : line.quantity(),
                        List.of()))
            .toList();
    return ApiResponse.ok(
        checkout.coverageQuote(
            Checks.required(body.memberUid(), "memberUid"),
            lines,
            body.preauthNumber(),
            body.customerUid(),
            body.pricePlan(),
            body.discountPercent()),
        "Coverage quoted");
  }

  // ---------------------------------------------------------------- claims

  @RequiresFeature(PlanFeature.INSURANCE)
  @GetMapping("/insurance/claims")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<Page<ClaimView>> claims(
      @RequestParam(required = false) UUID payerUid,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    ClaimStatus filter =
        status == null || status.isBlank()
            ? null
            : Checks.oneOf(ClaimStatus.class, status, "status");
    return ApiResponse.ok(
        insurance.claims(payerUid, filter, Pages.of(cursor, limit)).map(HealthFields::claim),
        "Claims retrieved");
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @GetMapping("/insurance/claims/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<ClaimView> claim(@PathVariable UUID uid) {
    return ApiResponse.ok(
        insurance
            .claim(uid)
            .map(HealthFields::claim)
            .orElseThrow(() -> Refusal.notFound("claim_not_found", "No such claim.")),
        "Claim retrieved");
  }

  /** The payer's decision; {@code rejectedTo} WRITE_OFF (default) or PATIENT_ACCOUNT. */
  @RequiresFeature(PlanFeature.INSURANCE)
  @PostMapping("/insurance/claims/{uid}/adjudicate")
  @Access(scope = AccessScope.TENANT, permission = "finance.manage")
  public ApiResponse<ClaimView> adjudicate(
      @PathVariable UUID uid, @RequestBody AdjudicationRequest body) {
    return ApiResponse.ok(
        HealthFields.claim(
            insurance.adjudicate(
                uid,
                new AdjudicationCommand(
                    body.status() == null || body.status().isBlank()
                        ? null
                        : Checks.oneOf(ClaimStatus.class, body.status(), "status"),
                    body.lines(),
                    body.note(),
                    body.rejectedTo() == null || body.rejectedTo().isBlank()
                        ? RejectedTo.WRITE_OFF
                        : Checks.oneOf(RejectedTo.class, body.rejectedTo(), "rejectedTo")))),
        "Claim adjudicated");
  }

  @GetMapping("/insurance/claims/totals")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<ClaimTotals> claimTotals() {
    return ApiResponse.ok(insurance.totals(), "Claim totals retrieved");
  }

  // ---------------------------------------------------------------- batches and remittances

  @RequiresFeature(PlanFeature.INSURANCE)
  @GetMapping("/insurance/batches")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<Page<ClaimBatchView>> batches(
      @RequestParam(required = false) UUID payerUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        insurance.batches(payerUid, Pages.of(cursor, limit)), "Batches retrieved");
  }

  /** Gathers the payer's draft claims of the period into a batch and submits them. */
  @RequiresFeature(PlanFeature.INSURANCE)
  @PostMapping("/insurance/batches")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "finance.manage")
  public ApiResponse<ClaimBatchView> batch(@RequestBody BatchRequest body) {
    return ApiResponse.created(
        insurance.batch(body.payerUid(), body.from(), body.to()), "Batch submitted");
  }

  /**
   * {@code ?format=CSV} or {@code JSON} (a FHIR R4 Bundle of Claim resources). Every claim names
   * its patient and carries the diagnosis (ICD-10): health data leaving in bulk, so the export
   * needs {@code data.export} and {@code health.view} besides {@code finance.view} (STD-5, ISO-10),
   * and each one is audited.
   */
  @RequiresFeature(PlanFeature.INSURANCE)
  @GetMapping("/insurance/batches/{uid}/export")
  @Access(
      scope = AccessScope.TENANT,
      allOf = {"finance.view", Permissions.DATA_EXPORT, HealthFields.PERMISSION})
  public ResponseEntity<byte[]> export(
      @PathVariable UUID uid, @RequestParam(required = false) String format) {
    boolean json =
        format != null && (format.equalsIgnoreCase("JSON") || format.equalsIgnoreCase("FHIR"));
    byte[] body = insurance.exportBatch(uid, json ? "JSON" : "CSV");
    audit.record(
        "CLAIM_BATCH_EXPORTED", "ClaimBatch", uid, null, Map.of("format", json ? "JSON" : "CSV"));
    return ResponseEntity.ok()
        .contentType(
            json
                ? MediaType.parseMediaType("application/fhir+json")
                : MediaType.parseMediaType("text/csv"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"claims-" + uid + (json ? ".fhir.json" : ".csv") + "\"")
        .body(body);
  }

  @RequiresFeature(PlanFeature.INSURANCE)
  @GetMapping("/insurance/remittances")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<Page<RemittanceView>> remittances(
      @RequestParam(required = false) UUID payerUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        insurance.remittances(payerUid, Pages.of(cursor, limit)), "Remittances retrieved");
  }

  /** A payer's payment, spread over claims as given or oldest approved first. */
  @RequiresFeature(PlanFeature.INSURANCE)
  @Idempotent
  @PostMapping("/insurance/remittances")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "finance.manage")
  public ApiResponse<List<ClaimView>> remit(@RequestBody RemittanceCommand body) {
    return ApiResponse.created(
        insurance.remit(body).stream().map(HealthFields::claim).toList(), "Remittance recorded");
  }

  // ---------------------------------------------------------------- bodies

  public record SchemeRequest(
      UUID payerUid,
      String name,
      String copayType,
      BigDecimal copayValue,
      BigDecimal annualLimit,
      Set<String> excludedProductTypes,
      Set<UUID> excludedProductUids,
      Boolean active) {

    SchemeCommand command() {
      return new SchemeCommand(
          payerUid,
          name,
          copayType == null || copayType.isBlank()
              ? CopayType.NONE
              : Checks.oneOf(CopayType.class, copayType, "copayType"),
          copayValue,
          annualLimit,
          excludedProductTypes == null ? Set.of() : excludedProductTypes,
          excludedProductUids == null ? Set.of() : excludedProductUids,
          active);
    }
  }

  public record QuoteLine(UUID productUid, Integer quantity) {}

  /**
   * {@code customerUid}, {@code pricePlan} and {@code discountPercent} as the sale will send them,
   * so the quote is priced exactly as checkout prices the sale.
   */
  public record QuoteRequest(
      UUID memberUid,
      List<QuoteLine> lines,
      String preauthNumber,
      UUID customerUid,
      String pricePlan,
      java.math.BigDecimal discountPercent) {}

  public record AdjudicationRequest(
      String status, List<ClaimLineView> lines, String note, String rejectedTo) {}

  public record BatchRequest(UUID payerUid, LocalDate from, LocalDate to) {}

  /** For the OpenAPI document: a remittance body. */
  public record RemittanceRequest(
      UUID payerUid,
      BigDecimal amount,
      String reference,
      LocalDate date,
      Map<UUID, BigDecimal> perClaim) {}
}
