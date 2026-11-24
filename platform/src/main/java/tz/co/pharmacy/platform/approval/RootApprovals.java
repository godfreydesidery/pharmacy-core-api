package tz.co.pharmacy.platform.approval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.Uids;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Two root administrators for the platform's irreversible steps (FRD-18, owner's decision of 24 Nov
 * 2026): lifting a pharmacy's legal hold and erasing a pharmacy. One root administrator asks;
 * another approves with their password within {@link #LIFETIME}, and the action runs once. The
 * requester can never approve their own request (also a database check).
 *
 * <p>Kept in PostgreSQL ({@code platform.root_approvals}), so every instance sees the same
 * requests. {@link #claim} moves a request out of PENDING under a row lock and commits before the
 * action runs: two approvals at once, on any instances, run the action at most once. If the action
 * is then refused, {@link #release} puts the request back to PENDING.
 *
 * <p>This is separate from {@link Approvals} (Batch 4), whose tokens belong to one pharmacy (its
 * rows are under that pharmacy's row-level security) and approve a pharmacy member's action.
 */
@Component
public class RootApprovals {

  /** How long a request waits for the second root administrator. */
  public static final Duration LIFETIME = Duration.ofHours(24);

  /** The actions that need two root administrators. */
  public static final String LIFT_LEGAL_HOLD = "LIFT_LEGAL_HOLD";

  public static final String ERASE_TENANT = "ERASE_TENANT";

  public static final List<String> ACTIONS = List.of(LIFT_LEGAL_HOLD, ERASE_TENANT);

  private static final String COLUMNS =
      "uid, action, subject_tenant_uid, reason, status, requested_by_uid, requested_by_name,"
          + " requested_at, expires_at, approved_by_uid, approved_by_name, approved_at,"
          + " cancelled_at";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate writes;
  private final Clock clock;
  private final AuditLog audit;

  RootApprovals(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      Clock clock,
      AuditLog audit) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.writes = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.audit = audit;
  }

  /** A request as the root console shows it; {@code status} is EXPIRED once past its time. */
  public record Request(
      UUID uid,
      String action,
      UUID tenantUid,
      String reason,
      String status,
      UUID requestedByUid,
      String requestedByName,
      Instant requestedAt,
      Instant expiresAt,
      UUID approvedByUid,
      String approvedByName,
      Instant approvedAt,
      Instant cancelledAt) {}

  /**
   * Opens a request. The caller has checked that a second active root administrator exists and that
   * the action may run now. 409 root_approval_pending while another is open for the same pharmacy
   * and action.
   */
  public Request request(
      String action, UUID tenantUid, String reason, UUID requesterUid, String requesterName) {
    String known = action(action);
    String why = Checks.text(reason, "reason", 500);
    Instant now = clock.instant();
    UUID uid = Uids.newUid();
    try {
      writes.executeWithoutResult(
          status -> {
            expireOld(tenantUid, known, now);
            jdbc.update(
                "insert into platform.root_approvals (uid, action, subject_tenant_uid, reason,"
                    + " requested_by_uid, requested_by_name, requested_at, expires_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?)",
                uid,
                known,
                tenantUid,
                why,
                requesterUid,
                requesterName,
                Timestamp.from(now),
                Timestamp.from(now.plus(LIFETIME)));
            audit.record(
                "ROOT_APPROVAL_REQUESTED",
                "Tenant",
                tenantUid,
                null,
                details(uid, known, Map.of("reason", why)));
          });
    } catch (DuplicateKeyException open) {
      throw Refusal.conflict(
              "root_approval_pending",
              "A request for this is already waiting for a second root administrator.")
          .with("action", known);
    }
    return find(uid);
  }

  /** The requests, newest first: only those waiting when {@code pendingOnly}. */
  public List<Request> list(boolean pendingOnly) {
    Timestamp now = Timestamp.from(clock.instant());
    return pendingOnly
        ? jdbc.query(
            "select "
                + COLUMNS
                + " from platform.root_approvals where status = 'PENDING' and expires_at > ?"
                + " order by requested_at desc limit 200",
            this::row,
            now)
        : jdbc.query(
            "select "
                + COLUMNS
                + " from platform.root_approvals order by requested_at desc"
                + " limit 200",
            this::row);
  }

  public Request find(UUID uid) {
    return jdbc
        .query("select " + COLUMNS + " from platform.root_approvals where uid = ?", this::row, uid)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> Refusal.notFound("root_approval_not_found", "No such approval request."));
  }

  /**
   * Takes a pending request for {@code approverUid} and commits it as EXECUTED before the action
   * runs, so it runs once. 403 approver_is_requester for the person who asked; 409
   * root_approval_closed once approved or cancelled; 409 root_approval_expired after {@link
   * #LIFETIME}.
   */
  public Request claim(UUID uid, UUID approverUid, String approverName) {
    Instant now = clock.instant();
    writes.executeWithoutResult(
        status -> {
          Request request =
              jdbc
                  .query(
                      "select "
                          + COLUMNS
                          + " from platform.root_approvals where uid = ? for update",
                      this::row,
                      uid)
                  .stream()
                  .findFirst()
                  .orElseThrow(
                      () ->
                          Refusal.notFound("root_approval_not_found", "No such approval request."));
          if (request.requestedByUid().equals(approverUid)) {
            throw Refusal.forbidden(
                "approver_is_requester",
                "A different root administrator must approve what you asked for.");
          }
          if ("EXPIRED".equals(request.status())
              || ("PENDING".equals(request.status()) && !request.expiresAt().isAfter(now))) {
            throw Refusal.conflict(
                "root_approval_expired",
                "This request waited more than 24 hours; ask again if it is still needed.");
          }
          if (!"PENDING".equals(request.status())) {
            throw Refusal.conflict(
                    "root_approval_closed", "This request was already approved or cancelled.")
                .with("status", request.status());
          }
          jdbc.update(
              "update platform.root_approvals set status = 'EXECUTED', approved_by_uid = ?,"
                  + " approved_by_name = ?, approved_at = ? where uid = ? and status = 'PENDING'",
              approverUid,
              approverName,
              Timestamp.from(now),
              uid);
          audit.record(
              "ROOT_APPROVAL_APPROVED",
              "Tenant",
              request.tenantUid(),
              null,
              details(
                  uid,
                  request.action(),
                  Map.of(
                      "requestedBy", request.requestedByName(),
                      "approvedBy", approverName,
                      "reason", request.reason())));
        });
    return find(uid);
  }

  /** The action was refused after the claim: the request waits again, unapproved. */
  public void release(UUID uid, String refusal) {
    writes.executeWithoutResult(
        status -> {
          jdbc.update(
              "update platform.root_approvals set status = 'PENDING', approved_by_uid = null,"
                  + " approved_by_name = null, approved_at = null"
                  + " where uid = ? and status = 'EXECUTED'",
              uid);
          Request request = find(uid);
          audit.record(
              "ROOT_APPROVAL_FAILED",
              "Tenant",
              request.tenantUid(),
              null,
              details(uid, request.action(), Map.of("refusal", String.valueOf(refusal))));
        });
  }

  /** Cancels a pending request (any root administrator may). */
  public Request cancel(UUID uid, UUID byUid) {
    Instant now = clock.instant();
    writes.executeWithoutResult(
        status -> {
          int changed =
              jdbc.update(
                  "update platform.root_approvals set status = 'CANCELLED', cancelled_by_uid = ?,"
                      + " cancelled_at = ? where uid = ? and status = 'PENDING'",
                  byUid,
                  Timestamp.from(now),
                  uid);
          Request request = find(uid);
          if (changed == 0) {
            throw Refusal.conflict(
                    "root_approval_closed", "This request was already approved or cancelled.")
                .with("status", request.status());
          }
          audit.record(
              "ROOT_APPROVAL_CANCELLED",
              "Tenant",
              request.tenantUid(),
              null,
              details(uid, request.action(), Map.of()));
        });
    return find(uid);
  }

  /** Requests past their time are marked EXPIRED, so a new one may be opened. */
  private void expireOld(UUID tenantUid, String action, Instant now) {
    jdbc.update(
        "update platform.root_approvals set status = 'EXPIRED' where subject_tenant_uid = ? and"
            + " action = ? and status = 'PENDING' and expires_at <= ?",
        tenantUid,
        action,
        Timestamp.from(now));
  }

  private static String action(String action) {
    String code = action == null ? "" : action.trim().toUpperCase(java.util.Locale.ROOT);
    if (!ACTIONS.contains(code)) {
      throw Refusal.invalid(
          "invalid_action", "The action must be one of " + ACTIONS + ".", "action");
    }
    return code;
  }

  private static Map<String, Object> details(UUID uid, String action, Map<String, Object> more) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("request", uid.toString());
    details.put("action", action);
    details.putAll(more);
    return details;
  }

  private Request row(ResultSet rs, int n) throws SQLException {
    Instant expires = rs.getTimestamp("expires_at").toInstant();
    String status = rs.getString("status");
    if ("PENDING".equals(status) && !expires.isAfter(clock.instant())) {
      status = "EXPIRED";
    }
    return new Request(
        rs.getObject("uid", UUID.class),
        rs.getString("action"),
        rs.getObject("subject_tenant_uid", UUID.class),
        rs.getString("reason"),
        status,
        rs.getObject("requested_by_uid", UUID.class),
        rs.getString("requested_by_name"),
        rs.getTimestamp("requested_at").toInstant(),
        expires,
        rs.getObject("approved_by_uid", UUID.class),
        rs.getString("approved_by_name"),
        instant(rs.getTimestamp("approved_at")),
        instant(rs.getTimestamp("cancelled_at")));
  }

  private static Instant instant(Timestamp at) {
    return at == null ? null : at.toInstant();
  }
}
