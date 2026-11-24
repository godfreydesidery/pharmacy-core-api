package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdverseReactions;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdverseReactions.ReportCommand;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * An adverse drug reaction report (STD-8). Written once; the patient's name is replaced when the
 * customer is anonymised (by the anonymisation's own SQL).
 */
@Getter
@Entity
@Table(schema = "inventory", name = "adverse_reactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdverseReaction extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String number;

  @Column(nullable = false, updatable = false)
  private UUID branchUid;

  @Column(updatable = false)
  private UUID customerUid;

  @Column(updatable = false)
  private String patientName;

  @Column(updatable = false)
  private Integer patientAge;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false)
  private AdverseReactions.Sex patientSex;

  @Column(updatable = false, precision = 5, scale = 1)
  private BigDecimal patientWeightKg;

  @Column(nullable = false, updatable = false)
  private UUID productUid;

  @Column(nullable = false, updatable = false)
  private String productName;

  @Column(updatable = false)
  private String batchNumber;

  @Column(updatable = false)
  private String dose;

  @Column(updatable = false)
  private String route;

  @Column(updatable = false)
  private String indication;

  @Column(updatable = false)
  private LocalDate therapyStarted;

  @Column(updatable = false)
  private LocalDate therapyStopped;

  @Column(nullable = false, updatable = false)
  private String reaction;

  @Column(nullable = false, updatable = false)
  private LocalDate onsetDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private AdverseReactions.Seriousness seriousness;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false)
  private AdverseReactions.SeriousReason seriousReason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private AdverseReactions.Outcome outcome;

  @Column(nullable = false, updatable = false)
  private String reporterName;

  @Column(updatable = false)
  private String reporterProfession;

  @Column(updatable = false)
  private String reporterPhone;

  @Column(updatable = false)
  private String note;

  /** A report of validated fields at the branch. */
  public AdverseReaction(String number, UUID branchUid, ReportCommand c) {
    this.number = number;
    this.branchUid = branchUid;
    this.customerUid = c.customerUid();
    this.patientName = c.patientName();
    this.patientAge = c.patientAge();
    this.patientSex = c.patientSex();
    this.patientWeightKg = c.patientWeightKg();
    this.productUid = c.productUid();
    this.productName = c.productName();
    this.batchNumber = c.batchNumber();
    this.dose = c.dose();
    this.route = c.route();
    this.indication = c.indication();
    this.therapyStarted = c.therapyStarted();
    this.therapyStopped = c.therapyStopped();
    this.reaction = c.reaction();
    this.onsetDate = c.onsetDate();
    this.seriousness = c.seriousness();
    this.seriousReason = c.seriousReason();
    this.outcome = c.outcome();
    this.reporterName = c.reporterName();
    this.reporterProfession = c.reporterProfession();
    this.reporterPhone = c.reporterPhone();
    this.note = c.note();
  }
}
