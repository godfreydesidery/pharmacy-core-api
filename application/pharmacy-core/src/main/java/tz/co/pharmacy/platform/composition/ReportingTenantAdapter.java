package tz.co.pharmacy.platform.composition;

import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.reporting.ports.outbound.ReportingTenant;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.persistence.TenantContext;

/** Binds reporting's reads of the current tenant to tenancy; reads only. */
@Component
class ReportingTenantAdapter implements ReportingTenant {

  private final TenantService tenants;
  private final SettingsService settings;

  ReportingTenantAdapter(TenantService tenants, SettingsService settings) {
    this.tenants = tenants;
    this.settings = settings;
  }

  @Override
  public ZoneId zone() {
    return tenants
        .find(TenantContext.requireTenant())
        .map(TenantView::timezone)
        .map(ZoneId::of)
        .orElse(ZoneOffset.UTC);
  }

  @Override
  public String currency() {
    return tenants.find(TenantContext.requireTenant()).map(TenantView::currency).orElse("TZS");
  }

  @Override
  public int expiryAlertDays() {
    return settings.current().expiryAlertDays();
  }
}
