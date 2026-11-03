package tz.co.pharmacy.modules.finance.domain;

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
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseCategory;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Money a branch spent, such as rent or the electricity bill. */
@Getter
@Entity
@Table(schema = "finance", name = "expenses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Expense extends TenantEntity {

  @Column(nullable = false)
  private String number;

  @Column(nullable = false)
  private UUID branchUid;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ExpenseCategory category;

  @Column(nullable = false)
  private String description;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false)
  private LocalDate expenseDate;

  @Column(nullable = false)
  private String paymentMethod;

  private String reference;

  public Expense(
      String number,
      UUID branchUid,
      ExpenseCategory category,
      String description,
      BigDecimal amount,
      LocalDate expenseDate,
      String paymentMethod,
      String reference) {
    this.number = number;
    this.branchUid = branchUid;
    this.category = category;
    this.description = description;
    this.amount = amount;
    this.expenseDate = expenseDate;
    this.paymentMethod = paymentMethod;
    this.reference = reference;
  }
}
