package tz.co.pharmacy.modules.purchasing.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderStatus;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** An order to a supplier for a branch; goods receipts fill it, line by line. */
@Getter
@Entity
@Table(schema = "purchasing", name = "purchase_orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder extends TenantEntity {

  private String number;
  private UUID branchUid;
  private UUID supplierUid;
  private String supplierName;

  @Enumerated(EnumType.STRING)
  private PurchaseOrderStatus status;

  private LocalDate expectedDate;
  private String note;
  private String cancelReason;
  private BigDecimal total;

  /** The document currency (ISO 4217); amounts and line costs are in it. */
  private String currency;

  /** Base units per 1 document unit, locked when the document was made. */
  @jakarta.persistence.Column(precision = 24, scale = 10)
  private BigDecimal exchangeRate;

  /** {@code total} in the tenant's base currency at {@code exchangeRate}. */
  private BigDecimal baseTotal;

  /** Prices the document in {@code currency} at {@code exchangeRate} base units per unit. */
  public void priceIn(String currency, BigDecimal exchangeRate) {
    this.currency = currency;
    this.exchangeRate = exchangeRate;
  }

  /** Records the base total, computed by the caller with the base currency's rounding. */
  public void settleBase(BigDecimal baseTotal) {
    this.baseTotal = baseTotal;
  }

  /**
   * Loaded for up to 100 documents at once when a list maps them (one query for the page's lines,
   * not one per document: adversarial review PERF-16).
   */
  @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  @org.hibernate.annotations.BatchSize(size = 100)
  private List<PurchaseOrderLine> lines = new ArrayList<>();

  public PurchaseOrder(
      String number,
      UUID branchUid,
      UUID supplierUid,
      String supplierName,
      LocalDate expectedDate,
      String note) {
    this.number = number;
    this.branchUid = branchUid;
    this.supplierUid = supplierUid;
    this.supplierName = supplierName;
    this.expectedDate = expectedDate;
    this.note = note;
    this.status = PurchaseOrderStatus.ORDERED;
    this.total = Amounts.ZERO;
  }

  public void addLine(UUID productUid, String productName, int quantity, BigDecimal unitCost) {
    PurchaseOrderLine line =
        new PurchaseOrderLine(this, productUid, productName, quantity, unitCost);
    lines.add(line);
    total = Amounts.money(total.add(line.getLineTotal()));
  }

  public boolean open() {
    return status == PurchaseOrderStatus.ORDERED
        || status == PurchaseOrderStatus.PARTIALLY_RECEIVED;
  }

  public void cancel(String reason) {
    this.status = PurchaseOrderStatus.CANCELLED;
    this.cancelReason = reason;
  }

  /** Received in full once every line is; partly once any unit is. */
  public void updateStatus() {
    boolean all = lines.stream().allMatch(line -> line.outstanding() == 0);
    boolean any = lines.stream().anyMatch(line -> line.getReceivedQuantity() > 0);
    status =
        all
            ? PurchaseOrderStatus.RECEIVED
            : any ? PurchaseOrderStatus.PARTIALLY_RECEIVED : PurchaseOrderStatus.ORDERED;
  }
}
