package tz.co.pharmacy.modules.tenancy.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.platform.persistence.MutableEntity;

/**
 * A pharmacy business: the tenant itself. Its uid is the tenant key every tenant row carries, so
 * this registry table alone has no isolation policy.
 */
@Getter
@Entity
@Table(schema = "tenancy", name = "tenants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant extends MutableEntity {

  private String code;
  private String name;
  private String legalName;
  private String country;
  private String currency;
  private String timezone;

  @Enumerated(EnumType.STRING)
  private TenantStatus status;

  private String statusReason;

  public Tenant(
      String code,
      String name,
      String legalName,
      String country,
      String currency,
      String timezone) {
    this.code = code;
    this.name = name;
    this.legalName = legalName;
    this.country = country;
    this.currency = currency;
    this.timezone = timezone;
    this.status = TenantStatus.ACTIVE;
  }

  public void suspend(String reason) {
    status = TenantStatus.SUSPENDED;
    statusReason = reason;
  }

  public void reactivate() {
    status = TenantStatus.ACTIVE;
    statusReason = null;
  }
}
