package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.inventory.ports.inbound.SerialStatus;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** One unit of a serial-tracked product: its serial number, branch and status. */
@Getter
@Entity
@Table(schema = "inventory", name = "serial_units")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SerialUnit extends TenantEntity {

  private UUID productUid;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "batch_id")
  private Batch batch;

  private String serialNumber;
  private UUID branchUid;

  @Enumerated(EnumType.STRING)
  private SerialStatus status;

  private String saleReference;
  private Instant soldAt;

  public SerialUnit(Batch batch, String serialNumber, UUID branchUid) {
    this.productUid = batch.getProductUid();
    this.batch = batch;
    this.serialNumber = serialNumber;
    this.branchUid = branchUid;
    this.status = SerialStatus.IN_STOCK;
  }

  public void sell(String reference) {
    status = SerialStatus.SOLD;
    saleReference = reference;
    soldAt = Instant.now();
  }

  /** Back in stock at the branch after a sales return; it keeps its sale history. */
  public void restock(UUID branch) {
    status = SerialStatus.IN_STOCK;
    branchUid = branch;
  }

  public void dispatch() {
    status = SerialStatus.IN_TRANSIT;
  }

  public void arriveAt(UUID branch) {
    status = SerialStatus.IN_STOCK;
    branchUid = branch;
  }

  public void writeOff() {
    status = SerialStatus.WRITTEN_OFF;
  }
}
