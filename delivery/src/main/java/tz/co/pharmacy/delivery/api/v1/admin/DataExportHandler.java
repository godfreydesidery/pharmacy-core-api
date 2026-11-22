package tz.co.pharmacy.delivery.api.v1.admin;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantData;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The pharmacy takes its data out: every table as CSV, its people and its files, in one ZIP. Open
 * to its administrators even while the account is unpaid or closed (the edge lets them read).
 */
@RestController
@RequestMapping("/api/v1/data-export")
public class DataExportHandler {

  private final TenantData data;
  private final TenantService tenants;
  private final Clock clock;

  public DataExportHandler(TenantData data, TenantService tenants, Clock clock) {
    this.data = data;
    this.tenants = tenants;
    this.clock = clock;
  }

  @GetMapping
  @Access(scope = AccessScope.TENANT, permission = Permissions.SETTINGS_MANAGE)
  public void export(HttpServletResponse response) {
    UUID tenantUid = TenantContext.requireTenant();
    DataExports.write(data, tenants, clock, tenantUid, response);
  }
}
