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

/** One batch consumed by a transformation. */
@Getter
@Entity
@Table(schema = "inventory", name = "transformation_inputs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransformationInput extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "transformation_id")
  private Transformation transformation;

  private UUID productUid;
  private String productName;
  private UUID batchUid;
  private String batchNumber;
  private LocalDate expiryDate;
  private int quantity;
  private BigDecimal unitCost;

  public TransformationInput(
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitCost) {
    this.productUid = productUid;
    this.productName = productName;
    this.batchUid = batchUid;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.quantity = quantity;
    this.unitCost = unitCost;
  }

  void attachTo(Transformation parent) {
    this.transformation = parent;
  }
}
