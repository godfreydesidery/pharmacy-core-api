package tz.co.pharmacy.modules.finance.domain;

import jakarta.persistence.CascadeType;
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
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A tenant's opening balances: the figures behind its opening journal entry, once per tenant. */
@Getter
@Entity
@Table(schema = "finance", name = "opening_balances")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpeningBalance extends TenantEntity {

  private LocalDate asOf;
  private BigDecimal cash;
  private BigDecimal bank;
  private BigDecimal mobileMoney;
  private BigDecimal inventory;
  private BigDecimal receivables = Amounts.ZERO;
  private BigDecimal payables = Amounts.ZERO;
  private BigDecimal capital = Amounts.ZERO;
  private String journalNumber;

  @OneToMany(mappedBy = "opening", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private List<OpeningBalanceLine> lines = new ArrayList<>();

  public OpeningBalance(
      LocalDate asOf,
      BigDecimal cash,
      BigDecimal bank,
      BigDecimal mobileMoney,
      BigDecimal inventory) {
    this.asOf = asOf;
    this.cash = Amounts.money(cash);
    this.bank = Amounts.money(bank);
    this.mobileMoney = Amounts.money(mobileMoney);
    this.inventory = Amounts.money(inventory);
  }

  /** A customer's debt or a supplier's credit; the totals follow. */
  public void addParty(String partyType, UUID partyUid, String partyName, BigDecimal amount) {
    BigDecimal value = Amounts.money(amount);
    lines.add(new OpeningBalanceLine(this, partyType, partyUid, partyName, value));
    if ("CUSTOMER".equals(partyType)) {
      receivables = Amounts.money(receivables.add(value));
    } else {
      payables = Amounts.money(payables.add(value));
    }
  }

  /** Owner's equity is what the assets are worth beyond what is owed. */
  public void close(String journalNumber) {
    this.capital =
        Amounts.money(
            cash.add(bank).add(mobileMoney).add(inventory).add(receivables).subtract(payables));
    this.journalNumber = journalNumber;
  }
}
