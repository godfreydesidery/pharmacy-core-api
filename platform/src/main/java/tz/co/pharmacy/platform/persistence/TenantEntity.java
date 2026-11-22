package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;
import lombok.Getter;

/**
 * A row that belongs to one tenant. Its {@code tenant_uid} is taken from {@link TenantContext} when
 * it is first saved, and the table's row-level security policy refuses any other value.
 *
 * <p>Every table of a tenant entity has these columns and policy:
 *
 * <pre>
 *   id bigint generated always as identity primary key,
 *   uid uuid not null unique,
 *   tenant_uid uuid not null,
 *   created_at timestamptz not null, created_by text not null, created_by_uid uuid,
 *   updated_at timestamptz, updated_by text, updated_by_uid uuid,
 *   version bigint not null default 0,
 *   ...
 *   alter table s.t enable row level security; alter table s.t force row level security;
 *   create policy tenant_isolation on s.t
 *     using (tenant_uid = platform.current_tenant_uid())
 *     with check (tenant_uid = platform.current_tenant_uid());
 * </pre>
 */
@Getter
@MappedSuperclass
public abstract class TenantEntity extends MutableEntity {

  @Column(nullable = false, updatable = false)
  private UUID tenantUid;

  @PrePersist
  void assignTenant() {
    if (tenantUid == null) {
      tenantUid = TenantContext.requireTenant();
    }
  }
}
