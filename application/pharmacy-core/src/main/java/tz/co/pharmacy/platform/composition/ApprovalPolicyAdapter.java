package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.platform.approval.ApprovalActions;
import tz.co.pharmacy.platform.approval.ApprovalPolicy;

/**
 * The pharmacy's approval rules: the actions that always need a manager (owner's list of 23 Nov
 * 2026), and the others above the approval limit set in Settings (TZS 50,000 by default).
 */
@Component
class ApprovalPolicyAdapter implements ApprovalPolicy {

  private final SettingsService settings;

  ApprovalPolicyAdapter(SettingsService settings) {
    this.settings = settings;
  }

  @Override
  public BigDecimal limitFor(String action) {
    if (ApprovalActions.ALWAYS.contains(action)) {
      return BigDecimal.ZERO;
    }
    if (!ApprovalActions.ABOVE_LIMIT.contains(action)) {
      return null;
    }
    BigDecimal limit = settings.current().approvalLimit();
    // A limit of 0 would mean "always"; a pharmacy that sets 0 asks for approval of every one.
    return limit == null ? null : limit;
  }
}
