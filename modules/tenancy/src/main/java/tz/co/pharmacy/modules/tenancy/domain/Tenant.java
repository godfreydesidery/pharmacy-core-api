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

  @Enumerated(EnumType.STRING)
  private tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind suspensionKind;

  private String contactName;

  private String contactEmail;

  private String contactPhone;

  private String address;

  /**
   * Whether the pharmacy's admin may create staff logins directly (managed logins), rather than
   * only invite people by email. Only the root administrator changes it.
   */
  private boolean directUserCreation = true;

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

  /** The editable registry fields; the code never changes. */
  public void change(
      String name,
      String legalName,
      String country,
      String timezone,
      String contactName,
      String contactEmail,
      String contactPhone,
      String address) {
    this.name = name;
    this.legalName = legalName;
    this.country = country;
    this.timezone = timezone;
    this.contactName = contactName;
    this.contactEmail = contactEmail;
    this.contactPhone = contactPhone;
    this.address = address;
  }

  public void allowDirectUserCreation(boolean allowed) {
    this.directUserCreation = allowed;
  }

  public void changeCurrency(String currency) {
    this.currency = currency;
  }

  public void suspend(
      tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind kind, String note) {
    status = TenantStatus.SUSPENDED;
    suspensionKind = kind;
    statusReason = note;
  }

  public void reactivate() {
    status = TenantStatus.ACTIVE;
    suspensionKind = null;
    statusReason = null;
  }

  /** Under a legal hold: its data may not be erased. */
  public boolean onLegalHold() {
    return status == TenantStatus.SUSPENDED
        && suspensionKind == tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind.DISPUTE;
  }
}
