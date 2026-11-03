package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The quantity of one batch at one branch. It changes only together with a {@link Movement}. */
@Getter
@Entity
@Table(schema = "inventory", name = "stock_levels")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockLevel extends tz.co.pharmacy.platform.persistence.TenantEntity {

  private UUID branchUid;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "batch_id")
  private Batch batch;

  private UUID productUid;
  private int quantity;

  public StockLevel(UUID branchUid, Batch batch) {
    this.branchUid = branchUid;
    this.batch = batch;
    this.productUid = batch.getProductUid();
    this.quantity = 0;
  }

  /** Adds a signed change; the caller has checked it never goes below zero. */
  public void change(int delta) {
    if (quantity + delta < 0) {
      throw new IllegalStateException("stock would go below zero");
    }
    quantity += delta;
  }
}
