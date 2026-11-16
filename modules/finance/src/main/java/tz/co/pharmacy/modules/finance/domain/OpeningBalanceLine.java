package tz.co.pharmacy.modules.finance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** One customer's opening debt or one supplier's opening credit. */
@Getter
@Entity
@Table(schema = "finance", name = "opening_balance_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpeningBalanceLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "opening_id", nullable = false)
  private OpeningBalance opening;

  private String partyType;
  private UUID partyUid;
  private String partyName;
  private BigDecimal amount;

  OpeningBalanceLine(
      OpeningBalance opening,
      String partyType,
      UUID partyUid,
      String partyName,
      BigDecimal amount) {
    this.opening = opening;
    this.partyType = partyType;
    this.partyUid = partyUid;
    this.partyName = partyName;
    this.amount = amount;
  }
}
