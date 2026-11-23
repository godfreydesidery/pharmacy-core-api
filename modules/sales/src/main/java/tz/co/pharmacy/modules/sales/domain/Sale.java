package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleStatus;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A completed sale (invoice) at one branch, with its lines and payments. */
@Entity
@Table(name = "sales", schema = "sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sale extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String number;

  @Column(nullable = false, updatable = false)
  private UUID branchUid;

  private String branchName;
  private UUID customerUid;
  private String customerName;

  /** The pricing plan it was priced under (null: Normal Sale), and its name then. */
  private UUID priceListUid;

  private String priceListName;

  /** What the sale would have cost at Normal Sale prices (null before pricing plans). */
  private BigDecimal normalTotal;

  /** CUSTOMER (the customer's own plan, not Normal Sale) or CHOSEN (switched); null otherwise. */
  private String pricePlanSource;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SaleStatus status;

  @Column(nullable = false, precision = 5, scale = 2)
  private BigDecimal discountPercent;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal subtotal;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal discountTotal;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal taxTotal;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal total;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal paidTotal;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal changeDue;

  private String idempotencyKey;
  private String prescriberName;
  private String prescriberRegistration;
  private String prescriptionReference;
  private String patientName;

  /**
   * The tax authority's reference of the fiscal receipt. Written once, after the sale committed, by
   * {@code SaleService.fiscalise} with SQL of its own: an update of the sale (a return) never
   * writes these two columns, so it cannot put back a value it read before the receipt was issued.
   */
  @Column(updatable = false)
  private String fiscalReference;

  /** Whether the fiscal receipt is still to be issued (a fiscal integration is on). */
  @Column(nullable = false, updatable = false)
  private boolean fiscalDue;

  private String cashierName;

  @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  @BatchSize(size = 50)
  private List<SaleLine> lines = new ArrayList<>();

  @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  @BatchSize(size = 50)
  private List<SalePayment> payments = new ArrayList<>();

  public Sale(
      String number,
      UUID branchUid,
      String branchName,
      UUID customerUid,
      String customerName,
      BigDecimal discountPercent,
      String idempotencyKey,
      String cashierName) {
    this.number = number;
    this.branchUid = branchUid;
    this.branchName = branchName;
    this.customerUid = customerUid;
    this.customerName = customerName;
    this.discountPercent = discountPercent;
    this.idempotencyKey = idempotencyKey;
    this.cashierName = cashierName;
    this.status = SaleStatus.COMPLETED;
  }

  public void pricedUnder(UUID planUid, String planName, BigDecimal normalTotal) {
    this.priceListUid = planUid;
    this.priceListName = planName;
    this.normalTotal = normalTotal;
  }

  /** Where the plan came from: CUSTOMER or CHOSEN; null for Normal Sale by default. */
  public void planFrom(String source) {
    this.pricePlanSource = source;
  }

  public void addLine(SaleLine line) {
    line.attachTo(this);
    lines.add(line);
  }

  public void addPayment(SalePayment payment) {
    payment.attachTo(this);
    payments.add(payment);
  }

  public void prescribe(
      String prescriberName,
      String prescriberRegistration,
      String prescriptionReference,
      String patientName) {
    this.prescriberName = prescriberName;
    this.prescriberRegistration = prescriberRegistration;
    this.prescriptionReference = prescriptionReference;
    this.patientName = patientName;
  }

  public void totals(SaleTotals.Totals totals, BigDecimal paidTotal, BigDecimal changeDue) {
    this.subtotal = totals.subtotal();
    this.discountTotal = totals.discountTotal();
    this.taxTotal = totals.taxTotal();
    this.total = totals.total();
    this.paidTotal = paidTotal;
    this.changeDue = changeDue;
  }

  /** A new sale whose fiscal receipt is issued after it commits (PERF-1). */
  public void fiscalReceiptDue(boolean due) {
    this.fiscalDue = due;
  }

  /** After a return: RETURNED once every unit came back, otherwise PARTIALLY_RETURNED. */
  public void refreshReturnStatus() {
    boolean all = lines.stream().allMatch(line -> line.getReturnedQuantity() >= line.getQuantity());
    boolean any = lines.stream().anyMatch(line -> line.getReturnedQuantity() > 0);
    if (all) {
      status = SaleStatus.RETURNED;
    } else if (any) {
      status = SaleStatus.PARTIALLY_RETURNED;
    } else {
      status = SaleStatus.COMPLETED;
    }
  }
}
