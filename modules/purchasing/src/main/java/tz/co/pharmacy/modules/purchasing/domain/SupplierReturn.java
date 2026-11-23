package tz.co.pharmacy.modules.purchasing.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
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
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Stock sent back to a supplier from one branch; its total is the debit note's value (base). */
@Getter
@Entity
@Table(schema = "purchasing", name = "supplier_returns")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplierReturn extends TenantEntity {

  private String number;
  private UUID branchUid;
  private UUID supplierUid;
  private String supplierName;
  private UUID goodsReceiptUid;
  private String goodsReceiptNumber;
  private String reason;
  private String note;
  private String createdByName;
  private BigDecimal total = BigDecimal.ZERO;

  /**
   * Loaded for up to 100 documents at once when a list maps them (one query for the page's lines,
   * not one per document: adversarial review PERF-16).
   */
  @OneToMany(mappedBy = "supplierReturn", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  @org.hibernate.annotations.BatchSize(size = 100)
  private List<SupplierReturnLine> lines = new ArrayList<>();

  public SupplierReturn(
      String number,
      UUID branchUid,
      UUID supplierUid,
      String supplierName,
      UUID goodsReceiptUid,
      String goodsReceiptNumber,
      String reason,
      String note) {
    this.number = number;
    this.branchUid = branchUid;
    this.supplierUid = supplierUid;
    this.supplierName = supplierName;
    this.goodsReceiptUid = goodsReceiptUid;
    this.goodsReceiptNumber = goodsReceiptNumber;
    this.reason = reason;
    this.note = note;
  }

  /** The recorder's display name, for the debit note. */
  public void recordedBy(String name) {
    this.createdByName = name;
  }

  public void addLine(SupplierReturnLine line) {
    line.attachTo(this);
    lines.add(line);
    total = Amounts.money(total.add(line.getLineTotal()));
  }
}
