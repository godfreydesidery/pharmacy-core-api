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

/** One batch destroyed: EXPIRED or DAMAGED, at its unit cost. */
@Getter
@Entity
@Table(schema = "inventory", name = "destruction_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DestructionLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "destruction_id")
  private Destruction destruction;

  private UUID productUid;
  private String productName;
  private UUID batchUid;
  private String batchNumber;
  private LocalDate expiryDate;
  private String reason;
  private int quantity;
  private BigDecimal unitCost;

  public DestructionLine(
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      String reason,
      int quantity,
      BigDecimal unitCost) {
    this.productUid = productUid;
    this.productName = productName;
    this.batchUid = batchUid;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.reason = reason;
    this.quantity = quantity;
    this.unitCost = unitCost;
  }

  void attachTo(Destruction destruction) {
    this.destruction = destruction;
  }
}
