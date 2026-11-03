package tz.co.pharmacy.modules.receivables.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Part of a credit entry that settled a debit entry. */
@Getter
@Entity
@Table(schema = "receivables", name = "allocations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Allocation extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "credit_entry_id", updatable = false)
  private Entry credit;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "debit_entry_id", updatable = false)
  private Entry debit;

  @Column(precision = 14, scale = 2, updatable = false)
  private BigDecimal amount;

  public Allocation(Entry credit, Entry debit, BigDecimal amount) {
    this.credit = credit;
    this.debit = debit;
    this.amount = Amounts.money(amount);
  }
}
