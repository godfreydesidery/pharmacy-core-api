package tz.co.pharmacy.modules.insurance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Setter
@Entity
@Table(schema = "insurance", name = "claim_lines")
@NoArgsConstructor
public class ClaimLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "claim_id")
  private Claim claim;

  private UUID productUid;
  private String productName;
  private int quantity;

  @Column(precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(precision = 14, scale = 2)
  private BigDecimal approvedAmount;

  private String rejectionReason;
}
