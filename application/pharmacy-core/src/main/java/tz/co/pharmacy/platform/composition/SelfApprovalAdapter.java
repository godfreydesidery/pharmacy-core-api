package tz.co.pharmacy.platform.composition;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.inbound.Approvers;
import tz.co.pharmacy.platform.approval.SelfApproval;

/** The owner-alone rule of self-approval, from the pharmacy's members in access. */
@Component
class SelfApprovalAdapter implements SelfApproval {

  // Looked up on use: access itself takes approvals (a credit limit, a role change).
  private final ObjectProvider<Approvers> approvers;

  SelfApprovalAdapter(ObjectProvider<Approvers> approvers) {
    this.approvers = approvers;
  }

  @Override
  public boolean allowed() {
    return approvers.getObject().selfApprovalAllowed();
  }
}
