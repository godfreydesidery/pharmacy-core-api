package tz.co.pharmacy.modules.receivables.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** One customer's account; its balance is the sum of its entries. */
@Getter
@Entity
@Table(schema = "receivables", name = "accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditAccount extends TenantEntity {

  @Column(updatable = false)
  private UUID customerUid;

  private String customerName;

  @Column(precision = 14, scale = 2)
  private BigDecimal creditLimit;

  private int termsDays;
  private boolean onHold;
  private String holdReason;

  public CreditAccount(
      UUID customerUid, String customerName, BigDecimal creditLimit, int termsDays) {
    this.customerUid = customerUid;
    refresh(customerName, creditLimit, termsDays);
  }

  /** Keeps the name, limit and terms in step with partners. */
  public void refresh(String customerName, BigDecimal creditLimit, int termsDays) {
    this.customerName = customerName;
    this.creditLimit = creditLimit == null ? BigDecimal.ZERO : creditLimit;
    this.termsDays = termsDays;
  }

  public void hold(boolean onHold, String reason) {
    this.onHold = onHold;
    this.holdReason = onHold ? reason : null;
  }
}
