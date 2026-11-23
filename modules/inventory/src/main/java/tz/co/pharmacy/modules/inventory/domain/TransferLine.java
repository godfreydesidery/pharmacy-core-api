package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** One batch's quantity in a transfer. */
@Getter
@Entity
@Table(schema = "inventory", name = "transfer_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "transfer_id")
  private Transfer transfer;

  private UUID productUid;
  private String productName;
  private UUID batchUid;
  private String batchNumber;
  private LocalDate expiryDate;
  private int quantity;

  /** Units that arrived; null until the transfer is received (and on transfers received before). */
  private Integer receivedQuantity;

  @Convert(converter = SerialNumbers.class)
  private List<String> serialNumbers = List.of();

  TransferLine(
      Transfer transfer,
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      List<String> serialNumbers) {
    this.transfer = transfer;
    this.productUid = productUid;
    this.productName = productName;
    this.batchUid = batchUid;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.quantity = quantity;
    this.serialNumbers = serialNumbers == null ? List.of() : List.copyOf(serialNumbers);
  }

  public void received(int quantity) {
    this.receivedQuantity = quantity;
  }
}
