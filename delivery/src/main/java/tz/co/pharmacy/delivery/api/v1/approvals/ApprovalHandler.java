package tz.co.pharmacy.delivery.api.v1.approvals;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.edge.EdgeFilter;
import tz.co.pharmacy.modules.access.ports.inbound.Approvers;
import tz.co.pharmacy.platform.approval.ApprovalActions;
import tz.co.pharmacy.platform.approval.Approvals;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * A manager approves, on the screen of the person asking, an action the API refused with {@code 403
 * approval_required}: they type their own sign-in and password; the answer is a single-use token
 * (two minutes) to send as {@code X-Approval} with the same request again. See {@link Approvals}.
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalHandler {

  private final Approvers approvers;
  private final Approvals approvals;

  public ApprovalHandler(Approvers approvers, Approvals approvals) {
    this.approvers = approvers;
    this.approvals = approvals;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT)
  @Transactional
  public ApiResponse<ApprovalResponse> approve(
      @RequestBody ApprovalRequest body, HttpServletRequest request) {
    ApprovalRequest given = body == null ? new ApprovalRequest(null, null, null, null, null) : body;
    String action = Checks.text(given.action(), "action", 40).toUpperCase(java.util.Locale.ROOT);
    if (!ApprovalActions.known(action)) {
      throw Refusal.invalid(
          "invalid_action", action + " is not an action that needs approval.", "action");
    }
    BigDecimal amount = given.amount() == null ? BigDecimal.ZERO : given.amount();
    if (amount.signum() < 0) {
      throw Refusal.invalid("invalid_amount", "amount cannot be negative.", "amount");
    }
    Approvers.Approver approver =
        approvers.verify(given.login(), given.password(), EdgeFilter.client(request));
    Approvals.Granted granted =
        approvals.grant(
            action,
            amount,
            ExecutionContext.actor().uid(),
            approver.userUid(),
            approver.name(),
            Checks.optionalText(given.reference(), "reference", 200),
            approver.selfApproved());
    return ApiResponse.created(
        new ApprovalResponse(
            granted.token(),
            granted.action(),
            granted.amount(),
            approver.name(),
            granted.expiresAt(),
            granted.selfApproved()),
        (granted.selfApproved() ? "Self-approved by " : "Approved by ") + approver.name());
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalRequest(
      String action, BigDecimal amount, String reference, String login, String password) {}

  /** {@code selfApproved}: the owner, alone able to approve here, approved their own request. */
  public record ApprovalResponse(
      String token,
      String action,
      BigDecimal amount,
      String approvedBy,
      Instant expiresAt,
      boolean selfApproved) {}
}
