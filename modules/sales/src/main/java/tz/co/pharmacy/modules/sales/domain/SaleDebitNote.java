package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.TaxCategory;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * A sales debit note (STD-2): the customer of a sale is charged more after its invoice, with VAT,
 * on their account. Its amounts never change; its fiscal status is written by SQL of its own.
 */
@Entity
@Table(name = "debit_notes", schema = "sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleDebitNote extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String number;

  @Column(nullable = false, updatable = false)
  private UUID branchUid;

  @Column(nullable = false, updatable = false)
  private UUID saleUid;

  @Column(nullable = false, updatable = false)
  private String saleNumber;

  @Column(nullable = false, updatable = false)
  private UUID customerUid;

  @Column(nullable = false, updatable = false)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private TaxCategory taxCategory;

  @Column(nullable = false, updatable = false)
  private BigDecimal taxRatePercent;

  @Column(nullable = false, updatable = false)
  private BigDecimal net;

  @Column(nullable = false, updatable = false)
  private BigDecimal tax;

  @Column(nullable = false, updatable = false)
  private BigDecimal total;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false)
  private tz.co.pharmacy.modules.sales.ports.inbound.FiscalStatus fiscalStatus;

  @Column(updatable = false)
  private String fiscalReference;

  public SaleDebitNote(
      String number,
      Sale sale,
      String reason,
      TaxCategory taxCategory,
      BigDecimal taxRatePercent,
      BigDecimal net,
      BigDecimal tax) {
    this.number = number;
    this.branchUid = sale.getBranchUid();
    this.saleUid = sale.getUid();
    this.saleNumber = sale.getNumber();
    this.customerUid = sale.getCustomerUid();
    this.reason = reason;
    this.taxCategory = taxCategory;
    this.taxRatePercent = taxRatePercent;
    this.net = net;
    this.tax = tax;
    this.total = net.add(tax);
  }
}
