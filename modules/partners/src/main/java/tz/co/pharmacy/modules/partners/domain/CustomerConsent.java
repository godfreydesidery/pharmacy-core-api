package tz.co.pharmacy.modules.partners.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.partners.ports.inbound.ConsentPurpose;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * One consent of a customer to one purpose: given at a moment by someone, and withdrawn later (the
 * same row). Giving again after a withdrawal starts a new row, so the history stays whole. A row
 * with no {@code givenAt} records a refusal noted when the customer was asked.
 */
@Getter
@Entity
@Table(schema = "partners", name = "customer_consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerConsent extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private UUID customerUid;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private ConsentPurpose purpose;

  @Column(updatable = false)
  private Instant givenAt;

  @Column(updatable = false)
  private String givenBy;

  private Instant withdrawnAt;
  private String withdrawnBy;
  private String note;

  public static CustomerConsent given(
      UUID customerUid, ConsentPurpose purpose, Instant at, String by, String note) {
    CustomerConsent consent = new CustomerConsent();
    consent.customerUid = customerUid;
    consent.purpose = purpose;
    consent.givenAt = at;
    consent.givenBy = by;
    consent.note = note;
    return consent;
  }

  public static CustomerConsent refused(
      UUID customerUid, ConsentPurpose purpose, Instant at, String by, String note) {
    CustomerConsent consent = new CustomerConsent();
    consent.customerUid = customerUid;
    consent.purpose = purpose;
    consent.withdrawnAt = at;
    consent.withdrawnBy = by;
    consent.note = note;
    return consent;
  }

  public boolean inForce() {
    return givenAt != null && withdrawnAt == null;
  }

  public void withdraw(Instant at, String by, String note) {
    this.withdrawnAt = at;
    this.withdrawnBy = by;
    if (note != null) {
      this.note = this.note == null ? note : this.note + " / " + note;
    }
  }
}
