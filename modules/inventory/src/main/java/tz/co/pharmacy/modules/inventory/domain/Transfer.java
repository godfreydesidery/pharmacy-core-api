package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferStatus;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Stock sent from one branch to another: in transit once dispatched, then received. */
@Getter
@Entity
@Table(schema = "inventory", name = "transfers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer extends TenantEntity {

  private String number;
  private UUID fromBranchUid;
  private UUID toBranchUid;

  @Enumerated(EnumType.STRING)
  private TransferStatus status;

  private String note;
  private Instant receivedAt;
  private String receivedBy;
  private UUID receivedByUid;

  @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private List<TransferLine> lines = new ArrayList<>();

  public Transfer(String number, UUID fromBranchUid, UUID toBranchUid, String note) {
    this.number = number;
    this.fromBranchUid = fromBranchUid;
    this.toBranchUid = toBranchUid;
    this.note = note;
    this.status = TransferStatus.IN_TRANSIT;
  }

  public void addLine(
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      List<String> serialNumbers) {
    lines.add(
        new TransferLine(
            this,
            productUid,
            productName,
            batchUid,
            batchNumber,
            expiryDate,
            quantity,
            serialNumbers));
  }

  public void markReceived(String by, UUID byUid) {
    this.status = TransferStatus.RECEIVED;
    this.receivedAt = Clocks.now();
    this.receivedBy = by;
    this.receivedByUid = byUid;
  }
}
