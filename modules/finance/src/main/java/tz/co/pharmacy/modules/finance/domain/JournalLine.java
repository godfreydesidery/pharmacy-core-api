package tz.co.pharmacy.modules.finance.domain;

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
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A debit or a credit of one account within an entry. */
@Getter
@Entity
@Table(schema = "finance", name = "journal_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "entry_id", nullable = false)
  private JournalEntry entry;

  @Column(nullable = false)
  private String accountCode;

  @Column(nullable = false)
  private String accountName;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal debit;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal credit;

  /** For reference only: the foreign currency and amount behind a base-currency line. */
  private String currency;

  @Column(precision = 14, scale = 2)
  private BigDecimal foreignAmount;

  JournalLine(
      JournalEntry entry,
      String accountCode,
      String accountName,
      BigDecimal debit,
      BigDecimal credit,
      String currency,
      BigDecimal foreignAmount) {
    this.entry = entry;
    this.accountCode = accountCode;
    this.accountName = accountName;
    this.debit = debit;
    this.credit = credit;
    this.currency = currency;
    this.foreignAmount = foreignAmount;
  }
}
