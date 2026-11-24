package tz.co.pharmacy.modules.partners.ports.inbound;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * The register of data-subject requests about the pharmacy's customers (STD-5): what was asked,
 * when it was received, when it is due, and who completed or refused it.
 */
public interface DataSubjectRequests {

  /**
   * Days to answer a request: 30 until counsel confirms (C7 in the privacy pack, "verify"). The
   * sources read give no fixed period.
   */
  int DUE_DAYS = 30;

  /**
   * Logs a request received on {@code receivedOn} (the pharmacy's day; null: today), OPEN. 422
   * customer_not_found (customerUid), invalid_received_on (after today).
   */
  DataSubjectRequestView log(RequestKind kind, UUID customerUid, LocalDate receivedOn, String note);

  /** Newest first; any filter may be null. */
  Page<DataSubjectRequestView> list(
      RequestStatus status, RequestKind kind, UUID customerUid, PageQuery query);

  Optional<DataSubjectRequestView> find(UUID uid);

  /**
   * Checks that {@code requestUid} may be completed by an action of {@code kind} on the customer:
   * 404 request_not_found; 409 request_closed (not OPEN); 422 request_mismatch (another kind or
   * customer). Nothing when {@code requestUid} is null.
   */
  void requireOpen(UUID requestUid, RequestKind kind, UUID customerUid);

  /**
   * Completes the OPEN request {@code requestUid} (checked as {@link #requireOpen}) or, when it is
   * null, records one of {@code kind} received and completed now.
   */
  DataSubjectRequestView complete(
      UUID requestUid, RequestKind kind, UUID customerUid, String outcome);

  /** Completes an OPEN request by hand (an objection answered by a consent change, ...). */
  DataSubjectRequestView complete(UUID requestUid, String outcome);

  /** Refuses an OPEN request, with the ground the Act allows (legal retention, ...). */
  DataSubjectRequestView refuse(UUID requestUid, String reason);
}
