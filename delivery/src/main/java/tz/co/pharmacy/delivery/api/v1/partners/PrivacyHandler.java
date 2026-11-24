package tz.co.pharmacy.delivery.api.v1.partners;

import java.time.LocalDate;
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
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.partners.ports.inbound.DataSubjectRequestView;
import tz.co.pharmacy.modules.partners.ports.inbound.DataSubjectRequests;
import tz.co.pharmacy.modules.partners.ports.inbound.PersonalData;
import tz.co.pharmacy.modules.partners.ports.inbound.RequestKind;
import tz.co.pharmacy.modules.partners.ports.inbound.RequestStatus;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Personal-data rights of one customer (adversarial review STD-5, Tanzania's PDPA 2022): a copy of
 * everything held about them, erasure by anonymisation, and the register of the requests. The
 * correction is the customer edit ({@code PUT /customers/{uid}?dataSubjectRequest=true}).
 *
 * <p>Who may: the request register and the copy need {@code data.export} (people's data leaving the
 * system, ISO-10); the copy holds prescriptions and diagnosis codes, so it needs {@code
 * health.view} too; the anonymisation changes customers for good and needs {@code partners.manage}
 * as well. Verify with counsel: the deadline (C7) and whether anonymisation answers an erasure
 * request (C10).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PrivacyHandler {

  private final PersonalData personalData;
  private final DataSubjectRequests requests;

  /**
   * The customer's personal data as a ZIP: {@code subject.json} and a CSV per kind of record.
   * Completes {@code requestUid} (an OPEN ACCESS request of this customer) or logs one; audited
   * (DATA_SUBJECT_EXPORT).
   */
  @PostMapping("/customers/{uid}/data-export")
  @Access(
      scope = AccessScope.TENANT,
      allOf = {"partners.view", Permissions.DATA_EXPORT, HealthFields.PERMISSION})
  public ResponseEntity<byte[]> export(
      @PathVariable UUID uid, @RequestParam(required = false) UUID requestUid) {
    PersonalData.Export export = personalData.export(uid, requestUid);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName() + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Request-Number", export.request().number())
        .body(export.zip());
  }

  /**
   * Erasure by anonymisation: 422 subject_has_balance (member {@code balance}: settle the account
   * first, either way), 422 subject_under_legal_hold, 409 customer_anonymised.
   */
  @PostMapping("/customers/{uid}/anonymise")
  @Access(
      scope = AccessScope.TENANT,
      allOf = {"partners.manage", Permissions.DATA_EXPORT})
  public ApiResponse<DataSubjectRequestView> anonymise(
      @PathVariable UUID uid, @RequestBody(required = false) AnonymiseRequest body) {
    UUID requestUid = body == null ? null : body.requestUid();
    String note = body == null ? null : Checks.optionalText(body.note(), "note", 500);
    return ApiResponse.ok(personalData.anonymise(uid, requestUid, note), "Customer anonymised");
  }

  public record AnonymiseRequest(UUID requestUid, String note) {}

  // ---------------------------------------------------------------- the request register

  @GetMapping("/privacy/requests")
  @Access(scope = AccessScope.TENANT, permission = Permissions.DATA_EXPORT)
  public ApiResponse<Page<DataSubjectRequestView>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) UUID customerUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        requests.list(
            blank(status) ? null : Checks.oneOf(RequestStatus.class, status, "status"),
            blank(kind) ? null : Checks.oneOf(RequestKind.class, kind, "kind"),
            customerUid,
            Pages.of(cursor, limit)),
        "Requests retrieved");
  }

  /** Logs a request as received: due {@link DataSubjectRequests#DUE_DAYS} days later (verify). */
  @PostMapping("/privacy/requests")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = Permissions.DATA_EXPORT)
  public ApiResponse<DataSubjectRequestView> log(@RequestBody LogRequest body) {
    if (body == null) {
      throw Refusal.malformed("A request is required.");
    }
    return ApiResponse.created(
        requests.log(
            Checks.oneOf(RequestKind.class, body.kind(), "kind"),
            body.customerUid(),
            body.receivedOn(),
            body.note()),
        "Request logged");
  }

  public record LogRequest(String kind, UUID customerUid, LocalDate receivedOn, String note) {}

  @GetMapping("/privacy/requests/{uid}")
  @Access(scope = AccessScope.TENANT, permission = Permissions.DATA_EXPORT)
  public ApiResponse<DataSubjectRequestView> request(@PathVariable UUID uid) {
    return ApiResponse.ok(
        requests
            .find(uid)
            .orElseThrow(() -> Refusal.notFound("request_not_found", "No such request.")),
        "Request retrieved");
  }

  /** Completes an OPEN request by hand, saying what was done. */
  @PostMapping("/privacy/requests/{uid}/complete")
  @Access(scope = AccessScope.TENANT, permission = Permissions.DATA_EXPORT)
  public ApiResponse<DataSubjectRequestView> complete(
      @PathVariable UUID uid, @RequestBody OutcomeRequest body) {
    return ApiResponse.ok(
        requests.complete(uid, body == null ? null : body.outcome()), "Request completed");
  }

  public record OutcomeRequest(String outcome) {}

  /** Refuses an OPEN request on a ground the Act allows (legal retention, legal claims, ...). */
  @PostMapping("/privacy/requests/{uid}/refuse")
  @Access(scope = AccessScope.TENANT, permission = Permissions.DATA_EXPORT)
  public ApiResponse<DataSubjectRequestView> refuse(
      @PathVariable UUID uid, @RequestBody ReasonRequest body) {
    return ApiResponse.ok(
        requests.refuse(uid, body == null ? null : body.reason()), "Request refused");
  }

  public record ReasonRequest(String reason) {}

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
