package tz.co.pharmacy.platform.composition;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.inventory.ports.outbound.BranchNames;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;

/** Binds inventory's branch names (transfers) to tenancy's branches of the current tenant. */
@Component
@RequiredArgsConstructor
class InventoryBranchAdapter implements BranchNames {

  private final BranchService branchService;

  @Override
  public String name(UUID branchUid) {
    return branchUid == null
        ? null
        : branchService.find(branchUid).map(BranchView::name).orElse(null);
  }
}
