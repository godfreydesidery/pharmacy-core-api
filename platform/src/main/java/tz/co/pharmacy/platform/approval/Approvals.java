package tz.co.pharmacy.platform.approval;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * A second person's approval for a sensitive action (segregation of duties; the PRD's POS-13,
 * INV-5, INV-7, ACC-1). The owner's rules of 23 Nov 2026: refunds, stock write-offs, vouchers, deep
 * discounts and promotions need a manager above the pharmacy's approval limit; some actions always
 * do.
 *
 * <p>How it runs, the same for every action:
 *
 * <ol>
 *   <li>A service calls {@link #require(String, BigDecimal, String)} inside its transaction. Below
 *       the limit nothing happens. Above it, without an approval, it refuses with {@code 403
 *       approval_required} and the problem's {@code action} and {@code amount}.
 *   <li>On the same screen a manager types their sign-in and password; {@code POST /approvals}
 *       checks them (another person, holding {@code approvals.grant} in this pharmacy and branch)
 *       and {@link #grant} keeps a single-use approval, valid {@link #LIFETIME}, for that action,
 *       amount and requester.
 *   <li>The request is sent again with {@code X-Approval: pap_…}; {@code require} consumes it in
 *       the same transaction (a rolled-back action leaves it unused) and audits who approved.
 * </ol>
 *
 * <p>Approvals live in {@code platform.approvals} (forced row-level security): any instance can
 * check one, and one cannot be used twice. A root administrator, acting with every permission,
 * needs none.
 */
@Component
public class Approvals {

  public static final Duration LIFETIME = Duration.ofMinutes(2);
  public static final String HEADER = "X-Approval";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate jdbc;
  private final ObjectProvider<ApprovalPolicy> policy;
  private final ObjectProvider<SelfApproval> selfApproval;
  private final AuditLog audit;
  private final Clock clock;

  Approvals(
      DataSource dataSource,
      ObjectProvider<ApprovalPolicy> policy,
      ObjectProvider<SelfApproval> selfApproval,
      AuditLog audit,
      Clock clock) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.policy = policy;
    this.selfApproval = selfApproval;
    this.audit = audit;
    this.clock = clock;
  }

  /** Whether {@code action} of {@code amount} needs an approval in this pharmacy. */
  public boolean needed(String action, BigDecimal amount) {
    if (ExecutionContext.rootAdmin()) {
      return false;
    }
    ApprovalPolicy rules = policy.getIfAvailable();
    BigDecimal limit = rules == null ? null : rules.limitFor(action);
    if (limit == null) {
      return false;
    }
    if (limit.signum() == 0) {
      return true;
    }
    return amount != null && Amounts.money(amount).compareTo(limit) > 0;
  }

  /**
   * Lets {@code action} of {@code amount} go ahead: at once when it needs no approval, otherwise
   * consuming the approval the request carries, or refusing with {@code 403 approval_required}.
   * {@code reference} (a sale number, a product…) goes into the audit trail. Returns the manager
   * who approved, or empty when none was needed (a destruction names them as its witness).
   */
  public Optional<Approver> require(String action, BigDecimal amount, String reference) {
    if (!needed(action, amount)) {
      return Optional.empty();
    }
    BigDecimal value = amount == null ? Amounts.ZERO : Amounts.money(amount);
    List<String> tokens = ApprovalContext.tokens();
    if (tokens.isEmpty()) {
      throw Refusal.forbidden(
              "approval_required",
              "A manager must approve this ("
                  + action.toLowerCase().replace('_', ' ')
                  + (value.signum() > 0 ? ", " + value.toPlainString() : "")
                  + ").")
          .with("action", action)
          .with("amount", value)
          .with("selfApproval", selfApprovalAllowed());
    }
    UUID requester = ExecutionContext.actor().uid();
    List<Map<String, Object>> used = List.of();
    // A request may carry several approvals (one per action it needs); use the one for this.
    for (String token : tokens) {
      used =
          jdbc.queryForList(
              "update platform.approvals set used_at = ?, used_reference = ?"
                  + " where token_hash = ? and action = ? and amount >= ? and requested_by_uid = ?"
                  + " and used_at is null and expires_at > ?"
                  + " returning approved_by_uid, approved_by_name, self_approved",
              Timestamp.from(clock.instant()),
              reference,
              hash(token),
              action,
              value,
              requester,
              Timestamp.from(clock.instant()));
      if (!used.isEmpty()) {
        break;
      }
    }
    if (used.isEmpty()) {
      boolean forThisAction =
          jdbc.queryForObject(
                  "select count(*) from platform.approvals where token_hash in ("
                      + String.join(",", java.util.Collections.nCopies(tokens.size(), "?"))
                      + ") and action = ?",
                  Integer.class,
                  java.util.stream.Stream.concat(
                          tokens.stream().map(Approvals::hash), java.util.stream.Stream.of(action))
                      .toArray())
              > 0;
      if (!forThisAction) {
        // The approvals sent are for other actions of this request: this one needs its own.
        throw Refusal.forbidden(
                "approval_required",
                "A manager must also approve this ("
                    + action.toLowerCase().replace('_', ' ')
                    + (value.signum() > 0 ? ", " + value.toPlainString() : "")
                    + ").")
            .with("action", action)
            .with("amount", value)
            .with("selfApproval", selfApprovalAllowed());
      }
      throw Refusal.forbidden(
              "approval_invalid",
              "That approval does not match this action, has expired or was used; ask again.")
          .with("action", action)
          .with("amount", value);
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("action", action);
    details.put("amount", value);
    details.put("reference", reference);
    details.put("approvedBy", used.getFirst().get("approved_by_name"));
    details.put("approvedByUid", String.valueOf(used.getFirst().get("approved_by_uid")));
    boolean self = Boolean.TRUE.equals(used.getFirst().get("self_approved"));
    details.put("selfApproved", self);
    audit.record("APPROVAL_USED", "Approval", requester, null, details);
    Object approverUid = used.getFirst().get("approved_by_uid");
    return Optional.of(
        new Approver(
            approverUid instanceof UUID uid ? uid : UUID.fromString(String.valueOf(approverUid)),
            String.valueOf(used.getFirst().get("approved_by_name")),
            self));
  }

  /** Whether the person asking may approve this themselves (the problem's selfApproval member). */
  private boolean selfApprovalAllowed() {
    SelfApproval rule = selfApproval.getIfAvailable();
    return rule != null && rule.allowed();
  }

  /**
   * The manager whose approval a request used; {@code selfApproved} when the pharmacy's owner,
   * alone able to approve there, approved their own request.
   */
  public record Approver(UUID uid, String name, boolean selfApproved) {

    public Approver(UUID uid, String name) {
      this(uid, name, false);
    }
  }

  /** What {@link #grant} hands back: the token to send as {@link #HEADER}, once. */
  public record Granted(
      String token, String action, BigDecimal amount, Instant expiresAt, boolean selfApproved) {}

  /**
   * Keeps an approval by {@code approverUid} of {@code action} up to {@code amount} for {@code
   * requesterUid}; the caller has checked the approver's password and permission.
   */
  public Granted grant(
      String action,
      BigDecimal amount,
      UUID requesterUid,
      UUID approverUid,
      String approverName,
      String reference) {
    return grant(action, amount, requesterUid, approverUid, approverName, reference, false);
  }

  /**
   * {@link #grant}, recording whether the requester approved their own request ({@code
   * selfApproved}: the owner alone, after typing their password; platform V11). A self-approval is
   * audited as APPROVAL_SELF_GRANTED.
   */
  public Granted grant(
      String action,
      BigDecimal amount,
      UUID requesterUid,
      UUID approverUid,
      String approverName,
      String reference,
      boolean selfApproved) {
    UUID tenant = TenantContext.requireTenant();
    BigDecimal value = amount == null ? Amounts.ZERO : Amounts.money(amount);
    byte[] random = new byte[32];
    RANDOM.nextBytes(random);
    String token = "pap_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    Instant now = clock.instant();
    Instant expires = now.plus(LIFETIME);
    jdbc.update(
        "insert into platform.approvals (uid, tenant_uid, token_hash, action, amount, reference,"
            + " requested_by_uid, approved_by_uid, approved_by_name, branch_uid, created_at,"
            + " expires_at, self_approved) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        tenant,
        hash(token),
        action,
        value,
        reference,
        requesterUid,
        approverUid,
        approverName,
        ExecutionContext.branchUid().orElse(null),
        Timestamp.from(now),
        Timestamp.from(expires),
        selfApproved);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("action", action);
    details.put("amount", value);
    details.put("reference", reference);
    details.put("requestedByUid", String.valueOf(requesterUid));
    details.put("approvedBy", approverName);
    details.put("selfApproved", selfApproved);
    audit.record(
        selfApproved ? "APPROVAL_SELF_GRANTED" : "APPROVAL_GRANTED",
        "Approval",
        approverUid,
        null,
        details);
    return new Granted(token, action, value, expires, selfApproved);
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
