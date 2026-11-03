package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesSettings;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Binds sales' reads of tenancy: the invoice prefix, the discount cap and the branches. */
@Component
@RequiredArgsConstructor
class SalesSettingsAdapter implements SalesSettings {

  private final SettingsService settingsService;
  private final BranchService branchService;

  @Override
  public String invoicePrefix() {
    String prefix = settingsService.current().invoicePrefix();
    return prefix == null || prefix.isBlank() ? "INV" : prefix;
  }

  @Override
  public BigDecimal maxDiscountPercent() {
    return settingsService.current().maxDiscountPercent();
  }

  @Override
  public String branchCode(UUID branchUid) {
    return branch(branchUid).code();
  }

  @Override
  public String branchName(UUID branchUid) {
    return branch(branchUid).name();
  }

  private BranchView branch(UUID branchUid) {
    return branchService
        .find(branchUid)
        .orElseThrow(() -> Refusal.invalid("branch_not_found", "No such branch."));
  }
}
