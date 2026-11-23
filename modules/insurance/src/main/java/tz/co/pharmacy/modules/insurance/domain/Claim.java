package tz.co.pharmacy.modules.insurance.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimStatus;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Setter
@Entity
@Table(schema = "insurance", name = "claims")
@NoArgsConstructor
public class Claim extends TenantEntity {
  private String number;
  private UUID payerUid;
  private UUID memberUid;
  private UUID customerUid;
  private String memberNumber;
  private String patientName;
  private UUID saleUid;
  private String saleNumber;
  private String preauthNumber;
  private String diagnosisCode;

  @Column(precision = 14, scale = 2)
  private BigDecimal claimed = Amounts.ZERO;

  @Column(precision = 14, scale = 2)
  private BigDecimal approved;

  @Column(precision = 14, scale = 2)
  private BigDecimal paid = Amounts.ZERO;

  /**
   * The rejected part as the books hold it: written off (6095) or charged to the patient (1100). A
   * re-adjudication or a return posts only the change from these, so the claim and the ledger keep
   * agreeing however often the claim is decided.
   */
  @Column(precision = 14, scale = 2)
  private BigDecimal writtenOff = Amounts.ZERO;

  @Column(precision = 14, scale = 2)
  private BigDecimal chargedToPatient = Amounts.ZERO;

  @Enumerated(EnumType.STRING)
  private ClaimStatus status = ClaimStatus.DRAFT;

  private UUID batchUid;
  private String note;

  @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private List<ClaimLine> lines = new ArrayList<>();

  public void add(ClaimLine line) {
    line.setClaim(this);
    lines.add(line);
    claimed = Amounts.money(claimed.add(line.getAmount()));
  }

  /** What the payer still owes: approved less paid. */
  public BigDecimal due() {
    return approved == null ? Amounts.ZERO : Amounts.money(approved.subtract(paid));
  }
}
