package tz.co.pharmacy.modules.inventory.domain;

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
import tz.co.pharmacy.modules.inventory.ports.inbound.TransformationType;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * Stock of some products turned into stock of another at one branch: the batches consumed and the
 * batch made, so a sold pack or preparation traces back to its source batches.
 */
@Getter
@Entity
@Table(schema = "inventory", name = "transformations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transformation extends TenantEntity {

  private String number;
  private UUID branchUid;

  @Enumerated(EnumType.STRING)
  private TransformationType type;

  private String note;
  private UUID outputProductUid;
  private String outputProductName;
  private UUID outputBatchUid;
  private String outputBatchNumber;
  private LocalDate outputExpiryDate;
  private int outputQuantity;
  private BigDecimal outputUnitCost;

  @OneToMany(mappedBy = "transformation", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private List<TransformationInput> inputs = new ArrayList<>();

  public Transformation(String number, UUID branchUid, TransformationType type, String note) {
    this.number = number;
    this.branchUid = branchUid;
    this.type = type;
    this.note = note;
  }

  public void addInput(TransformationInput input) {
    input.attachTo(this);
    inputs.add(input);
  }

  public void output(
      UUID productUid, String productName, Batch batch, int quantity, BigDecimal unitCost) {
    this.outputProductUid = productUid;
    this.outputProductName = productName;
    this.outputBatchUid = batch.getUid();
    this.outputBatchNumber = batch.getBatchNumber();
    this.outputExpiryDate = batch.getExpiryDate();
    this.outputQuantity = quantity;
    this.outputUnitCost = unitCost;
  }
}
