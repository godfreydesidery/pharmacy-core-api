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

  /**
   * Suspends a pharmacy for {@code kind} (OTHER when null), with a note saying why (required). A
   * DISPUTE suspension is a legal hold: the pharmacy's data may not be erased until it is lifted.
   */
  TenantView suspend(UUID tenantUid, SuspensionKind kind, String reason);

  /** 422 not_suspended when it is not suspended. A dispute's legal hold is lifted with it. */
  TenantView reactivate(UUID tenantUid);

  /**
   * Closes a pharmacy (its owner's request, or a lapsed account): nobody works in it, its admins
   * may still read and export, and after {@code coolingOffDays} a root admin may erase it. 422
   * already_closed.
   */
  TenantView close(UUID tenantUid, String reason, int coolingOffDays);

  /** Reopens a closed pharmacy before it is erased. 422 not_closed. */
  TenantView reopen(UUID tenantUid);

  /** Places a legal hold: the pharmacy's data may not be erased until it is lifted. */
  TenantView legalHold(UUID tenantUid, String reason);

  TenantView liftLegalHold(UUID tenantUid);

  PlatformCounts counts();

  /** The number of branches across every tenant (platform overview). */
  long branchTotal();
}
