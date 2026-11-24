package tz.co.pharmacy.modules.partners.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.partners.ports.inbound.RequestKind;
import tz.co.pharmacy.modules.partners.ports.inbound.RequestStatus;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A data-subject request about one customer: an entry of the request register (STD-5). */
@Getter
@Entity
@Table(schema = "partners", name = "data_subject_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataSubjectRequest extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String number;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private RequestKind kind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RequestStatus status = RequestStatus.OPEN;

  @Column(nullable = false, updatable = false)
  private UUID customerUid;

  @Column(nullable = false, updatable = false)
  private Instant receivedAt;

  @Column(nullable = false, updatable = false)
  private LocalDate dueOn;

  private Instant completedAt;
  private String completedBy;
  private UUID completedByUid;
  private String outcome;
  private String note;

  public DataSubjectRequest(
      String number,
      RequestKind kind,
      UUID customerUid,
      Instant receivedAt,
      LocalDate dueOn,
      String note) {
    this.number = number;
    this.kind = kind;
    this.customerUid = customerUid;
    this.receivedAt = receivedAt;
    this.dueOn = dueOn;
    this.note = note;
  }

  public void close(RequestStatus status, Instant at, String by, UUID byUid, String outcome) {
    this.status = status;
    this.completedAt = at;
    this.completedBy = by;
    this.completedByUid = byUid;
    this.outcome = outcome;
  }
}
