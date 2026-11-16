package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The tenant registry. */
public interface TenantService {

  /**
   * Platform scope. Writes the registry row, then, as the new tenant, its head office branch and
   * default settings, and publishes tenant.registered. Refuses a duplicate code: 409
   * duplicate_code.
   */
  TenantView register(RegisterTenantCommand command);

  /** A registry read, in any scope. */
  Optional<TenantView> find(UUID tenantUid);

  /** Platform scope, newest first; {@code q} matches code, name or legal name. */
  Page<TenantView> list(String q, PageQuery query);

  /**
   * Platform scope: edits the registry row. The currency changes only while the tenant has posted
   * nothing to its books (422 currency_locked); the settings' accepted currencies follow it.
   */
  TenantView change(UUID tenantUid, ChangeTenantCommand command);

  TenantView suspend(UUID tenantUid, String reason);

  TenantView reactivate(UUID tenantUid);

  PlatformCounts counts();

  /** The number of branches across every tenant (platform overview). */
  long branchTotal();
}
