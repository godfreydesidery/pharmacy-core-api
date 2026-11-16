package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** One batch on a count sheet: expected when the sheet was made, counted when entered. */
@Getter
@Entity
@Table(schema = "inventory", name = "stock_count_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCountLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "stock_count_id")
  private StockCount stockCount;

  private UUID productUid;
  private String productName;
  private UUID batchUid;
  private String batchNumber;
  private LocalDate expiryDate;
  private BigDecimal unitCost;
  private int expected;
  private Integer counted;

  public StockCountLine(
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      BigDecimal unitCost,
      int expected) {
    this.productUid = productUid;
    this.productName = productName;
    this.batchUid = batchUid;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.unitCost = unitCost;
    this.expected = expected;
  }

  public void counted(Integer counted) {
    this.counted = counted;
  }

  void attachTo(StockCount count) {
    this.stockCount = count;
  }
}
