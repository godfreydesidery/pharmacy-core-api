package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustmentReason;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** One entry of the append-only stock ledger. */
@Getter
@Entity
@Table(schema = "inventory", name = "movements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Movement extends TenantEntity {

  private UUID branchUid;
  private UUID productUid;
  private String productName;
  private UUID batchUid;
  private String batchNumber;

  @Enumerated(EnumType.STRING)
  private MovementType type;

  private int quantityChange;
  private int balanceAfter;
  private String reference;

  @Enumerated(EnumType.STRING)
  private AdjustmentReason reasonCode;

  private String note;

  @Convert(converter = SerialNumbers.class)
  private List<String> serialNumbers = List.of();

  public Movement(
      UUID branchUid,
      String productName,
      Batch batch,
      MovementType type,
      int quantityChange,
      int balanceAfter,
      String reference,
      AdjustmentReason reasonCode,
      String note) {
    this.branchUid = branchUid;
    this.productUid = batch.getProductUid();
    this.productName = productName;
    this.batchUid = batch.getUid();
    this.batchNumber = batch.getBatchNumber();
    this.type = type;
    this.quantityChange = quantityChange;
    this.balanceAfter = balanceAfter;
    this.reference = reference;
    this.reasonCode = reasonCode;
    this.note = note;
  }

  /** As the other constructor, recording the serial numbers of the units moved. */
  public Movement(
      UUID branchUid,
      String productName,
      Batch batch,
      MovementType type,
      int quantityChange,
      int balanceAfter,
      String reference,
      AdjustmentReason reasonCode,
      String note,
      List<String> serialNumbers) {
    this(
        branchUid,
        productName,
        batch,
        type,
        quantityChange,
        balanceAfter,
        reference,
        reasonCode,
        note);
    this.serialNumbers = serialNumbers == null ? List.of() : List.copyOf(serialNumbers);
  }
}
