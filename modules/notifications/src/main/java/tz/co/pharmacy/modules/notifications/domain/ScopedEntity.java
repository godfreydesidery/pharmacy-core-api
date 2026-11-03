package tz.co.pharmacy.modules.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;
import lombok.Getter;
import tz.co.pharmacy.platform.persistence.MutableEntity;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * A row of the current tenant, or of the platform when there is none: a root administrator's
 * password reset belongs to no tenant. The table's policy shows platform rows only to a session
 * without a tenant.
 */
@Getter
@MappedSuperclass
public abstract class ScopedEntity extends MutableEntity {

  @Column(updatable = false)
  private UUID tenantUid;

  @PrePersist
  void assignScope() {
    if (tenantUid == null) {
      tenantUid = TenantContext.tenantUid().orElse(null);
    }
  }
}
