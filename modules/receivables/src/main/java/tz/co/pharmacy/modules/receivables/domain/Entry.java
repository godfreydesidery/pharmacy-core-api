package tz.co.pharmacy.modules.receivables.domain;

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
import tz.co.pharmacy.modules.receivables.ports.inbound.EntryType;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * One movement of an account, in the base currency: {@code amount} is signed (debits positive),
 * {@code openAmount} is what is still unpaid of a debit or unallocated of a credit.
 */
@Getter
@Entity
@Table(schema = "receivables", name = "entries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Entry extends TenantEntity {

  @Column(updatable = false)
  private UUID customerUid;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false)
  private EntryType type;

  @Column(updatable = false)
  private String reference;

  @Column(updatable = false)
  private UUID saleUid;

  @Column(precision = 14, scale = 2, updatable = false)
  private BigDecimal amount;

  @Column(precision = 14, scale = 2)
  private BigDecimal openAmount;

  @Column(updatable = false)
  private LocalDate entryDate;

  @Column(updatable = false)
  private LocalDate dueDate;

  @Column(updatable = false)
  private String note;

  @Column(updatable = false)
  private String method;

  @Column(updatable = false)
  private String currency;

  @Column(precision = 14, scale = 2, updatable = false)
  private BigDecimal foreignAmount;

  @Column(precision = 18, scale = 8, updatable = false)
  private BigDecimal exchangeRate;

  private Entry(
      UUID customerUid,
      EntryType type,
      String reference,
      UUID saleUid,
      BigDecimal amount,
      BigDecimal openAmount,
      LocalDate entryDate,
      LocalDate dueDate,
      String note) {
    this.customerUid = customerUid;
    this.type = type;
    this.reference = reference;
    this.saleUid = saleUid;
    this.amount = Amounts.money(amount);
    this.openAmount = Amounts.money(openAmount);
    this.entryDate = entryDate;
    this.dueDate = dueDate;
    this.note = note;
  }

  /** A debit: what the customer now owes. */
  public static Entry debit(
      UUID customerUid,
      EntryType type,
      String reference,
      UUID saleUid,
      BigDecimal amount,
      boolean open,
      LocalDate date,
      LocalDate due,
      String note) {
    return new Entry(
        customerUid,
        type,
        reference,
        saleUid,
        amount,
        open ? amount : BigDecimal.ZERO,
        date,
        due,
        note);
  }

  /** A credit: stored negative, with all of it unallocated until it settles debits. */
  public static Entry credit(
      UUID customerUid,
      EntryType type,
      String reference,
      UUID saleUid,
      BigDecimal amount,
      LocalDate date,
      String note) {
    return new Entry(
        customerUid, type, reference, saleUid, amount.negate(), amount, date, null, note);
  }

  public Entry paidIn(String method, String currency, BigDecimal foreignAmount, BigDecimal rate) {
    this.method = method;
    this.currency = currency;
    this.foreignAmount = foreignAmount == null ? null : Amounts.money(foreignAmount);
    this.exchangeRate = rate;
    return this;
  }

  public boolean isDebit() {
    return amount.signum() > 0;
  }

  /** Takes {@code value} off the open amount; never below zero. */
  public void consume(BigDecimal value) {
    BigDecimal next = openAmount.subtract(value);
    if (next.signum() < 0) {
      throw new IllegalStateException("allocation exceeds the open amount of " + getUid());
    }
    openAmount = Amounts.money(next);
  }
}
