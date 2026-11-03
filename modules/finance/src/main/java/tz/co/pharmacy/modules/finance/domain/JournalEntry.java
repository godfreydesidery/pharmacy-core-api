package tz.co.pharmacy.modules.finance.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * One balanced journal entry, posted once per source document (a sale, a return, a goods receipt,
 * an expense). Entries are append-only: a correction is a new, reversing entry.
 */
@Getter
@Entity
@Table(schema = "finance", name = "journal_entries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry extends TenantEntity {

  @Column(nullable = false)
  private String number;

  @Column(nullable = false)
  private LocalDate entryDate;

  @Column(nullable = false)
  private String sourceType;

  @Column(nullable = false)
  private UUID sourceUid;

  private String sourceReference;

  @Column(nullable = false)
  private String description;

  private UUID branchUid;

  @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private List<JournalLine> lines = new ArrayList<>();

  public JournalEntry(
      String number,
      LocalDate entryDate,
      String sourceType,
      UUID sourceUid,
      String sourceReference,
      String description,
      UUID branchUid) {
    this.number = number;
    this.entryDate = entryDate;
    this.sourceType = sourceType;
    this.sourceUid = sourceUid;
    this.sourceReference = sourceReference;
    this.description = description;
    this.branchUid = branchUid;
  }

  public void addLine(String accountCode, String accountName, BigDecimal debit, BigDecimal credit) {
    addLine(accountCode, accountName, debit, credit, null, null);
  }

  /** A base-currency line that also notes the foreign currency and amount behind it. */
  public void addLine(
      String accountCode,
      String accountName,
      BigDecimal debit,
      BigDecimal credit,
      String currency,
      BigDecimal foreignAmount) {
    lines.add(
        new JournalLine(this, accountCode, accountName, debit, credit, currency, foreignAmount));
  }
}
