package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Prepaid value, sold for money and spent at the till by its code until the balance is used. */
@Getter
@Entity
@Table(schema = "sales", name = "vouchers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Voucher extends TenantEntity {

  private String code;
  private BigDecimal initialValue;
  private BigDecimal balance;
  private LocalDate expiresOn;
  private String holderName;
  private String paidBy;
  private String status;

  /** Where it was sold (null for vouchers sold before branches were recorded). */
  private UUID branchUid;

  public Voucher(
      String code,
      BigDecimal value,
      LocalDate expiresOn,
      String holderName,
      String paidBy,
      UUID branchUid) {
    this.code = code;
    this.initialValue = Amounts.money(value);
    this.balance = Amounts.money(value);
    this.expiresOn = expiresOn;
    this.holderName = holderName;
    this.paidBy = paidBy;
    this.status = "ACTIVE";
    this.branchUid = branchUid;
  }

  public void spend(BigDecimal amount) {
    this.balance = Amounts.money(balance.subtract(amount));
    if (balance.signum() == 0) {
      this.status = "SPENT";
    }
  }

  /**
   * A return of a sale paid with this voucher: the voucher's share of the refund goes back onto its
   * balance, so the liability the ledger books back (2300) is the customer's to spend again. A
   * used-up voucher becomes spendable again; a voided one stays void.
   */
  public void refunded(BigDecimal amount) {
    this.balance = Amounts.money(balance.add(amount));
    if ("SPENT".equals(status) && balance.signum() > 0) {
      this.status = "ACTIVE";
    }
  }

  public void voided() {
    this.status = "VOID";
  }
}
