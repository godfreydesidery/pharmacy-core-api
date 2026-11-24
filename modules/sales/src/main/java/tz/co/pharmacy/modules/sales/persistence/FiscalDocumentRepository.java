package tz.co.pharmacy.modules.sales.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.sales.domain.FiscalDocument;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentKind;

/**
 * The fiscal documents. Every change after a document is written is an update of its own here (see
 * {@link FiscalDocument}); each clears the persistence context, so the document read next is the
 * row as it now is.
 */
public interface FiscalDocumentRepository
    extends JpaRepository<FiscalDocument, Long>, JpaSpecificationExecutor<FiscalDocument> {

  Optional<FiscalDocument> findByUid(UUID uid);

  Optional<FiscalDocument> findByKindAndSourceUid(FiscalDocumentKind kind, UUID sourceUid);

  /** A sale's documents, oldest first: its receipt, then its credit notes. */
  List<FiscalDocument> findBySaleUidOrderByIdAsc(UUID saleUid);

  /**
   * Takes the right to send the document until {@code until} (a lease): only while it is PENDING
   * and nobody else holds an unexpired lease. 1 when taken, 0 otherwise.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents set claimed_until = :until where uid = :uid"
              + " and status = 'PENDING' and (claimed_until is null or claimed_until < :now)",
      nativeQuery = true)
  int claim(@Param("uid") UUID uid, @Param("now") Instant now, @Param("until") Instant until);

  /** The pharmacy's TIN and VRN as this attempt sends them (corrected settings are resent). */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents set seller_tin = cast(:tin as text),"
              + " seller_vrn = cast(:vrn as text) where uid = :uid",
      nativeQuery = true)
  int seller(@Param("uid") UUID uid, @Param("tin") String tin, @Param("vrn") String vrn);

  /** Keeps the counters the document was first sent with; never changes them afterwards. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents set global_counter = :global, daily_counter = :daily"
              + " where uid = :uid and global_counter is null",
      nativeQuery = true)
  int count(@Param("uid") UUID uid, @Param("global") long global, @Param("daily") Integer daily);

  /** The authority issued it: its number, code and QR payload; the lease ends. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents set status = 'ISSUED', receipt_number = cast(:number as text),"
              + " verification_code = cast(:code as text), qr_payload = cast(:qr as text),"
              + " issued_at = :at, attempts = attempts + 1, last_error = null,"
              + " claimed_until = null, updated_at = :at"
              + " where uid = :uid and status = 'PENDING'",
      nativeQuery = true)
  int issued(
      @Param("uid") UUID uid,
      @Param("number") String number,
      @Param("code") String verificationCode,
      @Param("qr") String qrPayload,
      @Param("at") Instant at);

  /** The authority refused it for good: FAILED with its reason; the lease ends. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents set status = 'FAILED', attempts = attempts + 1,"
              + " last_error = :error, claimed_until = null, updated_at = :at"
              + " where uid = :uid and status = 'PENDING'",
      nativeQuery = true)
  int refused(@Param("uid") UUID uid, @Param("error") String error, @Param("at") Instant at);

  /** An attempt failed (the authority did not answer): it stays PENDING; the lease ends. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents set attempts = attempts + 1, last_error = :error,"
              + " claimed_until = null, updated_at = :at where uid = :uid and status = 'PENDING'",
      nativeQuery = true)
  int attemptFailed(@Param("uid") UUID uid, @Param("error") String error, @Param("at") Instant at);

  /** A FAILED document is to be sent again: PENDING. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents set status = 'PENDING', claimed_until = null,"
              + " updated_at = :at where uid = :uid and status = 'FAILED'",
      nativeQuery = true)
  int reopen(@Param("uid") UUID uid, @Param("at") Instant at);

  /**
   * A Z report closes the branch's issued receipts and credit notes of {@code day} and before that
   * no Z report closed yet. Returns how many it closed.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents set z_report_uid = :z where branch_uid = :branch"
              + " and kind <> 'Z_REPORT' and status = 'ISSUED' and z_report_uid is null"
              + " and business_date <= :day",
      nativeQuery = true)
  int close(
      @Param("z") UUID zReportUid,
      @Param("branch") UUID branchUid,
      @Param("day") java.time.LocalDate day);

  /** The figures of a Z report, from the documents it closed. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "update sales.fiscal_documents z set"
              + " z_receipt_count = f.receipts, z_receipt_total = f.receipts_total,"
              + " z_receipt_tax = f.receipts_tax, z_credit_note_count = f.notes,"
              + " z_credit_note_total = f.notes_total, z_credit_note_tax = f.notes_tax,"
              + " total = f.receipts_total - f.notes_total, tax_total = f.receipts_tax - f.notes_tax,"
              + " standard_net = f.standard_net, zero_rated_net = f.zero_rated_net,"
              + " exempt_net = f.exempt_net, global_counter = f.last_counter"
              + " from (select"
              + "   count(*) filter (where d.kind = 'RECEIPT') as receipts,"
              + "   coalesce(sum(d.total) filter (where d.kind = 'RECEIPT'), 0) as receipts_total,"
              + "   coalesce(sum(d.tax_total) filter (where d.kind = 'RECEIPT'), 0) as receipts_tax,"
              + "   count(*) filter (where d.kind = 'CREDIT_NOTE') as notes,"
              + "   coalesce(sum(d.total) filter (where d.kind = 'CREDIT_NOTE'), 0) as notes_total,"
              + "   coalesce(sum(d.tax_total) filter (where d.kind = 'CREDIT_NOTE'), 0) as notes_tax,"
              + "   coalesce(sum(case when d.kind = 'RECEIPT' then d.standard_net"
              + "     else -d.standard_net end), 0) as standard_net,"
              + "   coalesce(sum(case when d.kind = 'RECEIPT' then d.zero_rated_net"
              + "     else -d.zero_rated_net end), 0) as zero_rated_net,"
              + "   coalesce(sum(case when d.kind = 'RECEIPT' then d.exempt_net"
              + "     else -d.exempt_net end), 0) as exempt_net,"
              + "   max(d.global_counter) as last_counter"
              + "   from sales.fiscal_documents d where d.z_report_uid = :z) f"
              + " where z.uid = :z",
      nativeQuery = true)
  int sumUp(@Param("z") UUID zReportUid);

  /** The lowest counter a Z report closed, or null when it closed none. */
  @Query(
      value = "select min(global_counter) from sales.fiscal_documents where z_report_uid = :z",
      nativeQuery = true)
  Long firstCounter(@Param("z") UUID zReportUid);

  /**
   * PENDING documents created before {@code before} whose lease is free, oldest first: what the
   * fiscal job sends again.
   */
  @Query(
      value =
          "select uid from sales.fiscal_documents where status = 'PENDING' and created_at < :before"
              + " and (claimed_until is null or claimed_until < :now) order by created_at limit :limit",
      nativeQuery = true)
  List<UUID> pendingBefore(
      @Param("before") Instant before, @Param("now") Instant now, @Param("limit") int limit);

  /**
   * The (branch, day) pairs before {@code today} with issued receipts or credit notes no Z report
   * closed, oldest first; a day on or before which the branch already has a Z report is left out
   * (its late documents go into the branch's next day's report).
   */
  @Query(
      value =
          "select distinct d.branch_uid, d.business_date from sales.fiscal_documents d"
              + " where d.kind <> 'Z_REPORT' and d.status = 'ISSUED' and d.z_report_uid is null"
              + " and d.business_date < :today"
              + " and d.branch_uid in (:branches)"
              + " and not exists (select 1 from sales.fiscal_documents z where z.kind = 'Z_REPORT'"
              + "   and z.branch_uid = d.branch_uid and z.business_date >= d.business_date)"
              + " order by d.business_date limit 31",
      nativeQuery = true)
  List<Object[]> daysToClose(
      @Param("today") java.time.LocalDate today,
      @Param("branches") java.util.Collection<UUID> branchUids);

  /** The branches with issued receipts or credit notes that no Z report closed yet. */
  @Query(
      value =
          "select distinct branch_uid from sales.fiscal_documents where kind <> 'Z_REPORT'"
              + " and status = 'ISSUED' and z_report_uid is null",
      nativeQuery = true)
  List<UUID> branchesToClose();
}
