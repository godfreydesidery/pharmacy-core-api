package tz.co.pharmacy.delivery.api.v1.finance;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpiryProvisions;
import tz.co.pharmacy.modules.finance.ports.inbound.MonthEndRuns;
import tz.co.pharmacy.modules.finance.ports.inbound.VatApportionments;

/**
 * The month-end runs by hand (the jobs make them on the first days of each month): the last
 * complete month's expiry write-down (STD-3) and VAT apportionment (STD-2). A month runs once;
 * asking again answers with what was recorded.
 */
@RestController
@RequestMapping("/api/v1/finance")
public class MonthEndHandler {

  private final MonthEndRuns months;

  public MonthEndHandler(MonthEndRuns months) {
    this.months = months;
  }

  @PostMapping("/expiry-provision/runs")
  @Access(scope = AccessScope.TENANT, permission = Permissions.FINANCE_MANAGE)
  public ApiResponse<ExpiryProvisions.ExpiryProvisionRun> expiryProvision() {
    return ApiResponse.ok(
        months.expiryProvision(months.lastCompleteMonth()), "Expiry write-down measured");
  }

  @PostMapping("/vat-apportionment/runs")
  @Access(scope = AccessScope.TENANT, permission = Permissions.FINANCE_MANAGE)
  public ApiResponse<List<VatApportionments.VatApportionmentRun>> vatApportionment() {
    return ApiResponse.ok(
        months.vatApportionment(months.lastCompleteMonth()), "VAT apportionment measured");
  }
}
