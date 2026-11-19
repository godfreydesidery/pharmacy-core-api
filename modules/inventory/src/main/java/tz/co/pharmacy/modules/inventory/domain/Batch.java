package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * A batch (lot) of a product, with its expiry (null for a product without one) and unit cost; any
 * branch may hold some of it.
 */
@Getter
@Entity
@Table(schema = "inventory", name = "batches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Batch extends TenantEntity {

  private UUID productUid;
  private String batchNumber;
  private LocalDate expiryDate;
  private BigDecimal unitCost;
  private boolean quarantined;
  private String quarantineReason;
  private Instant quarantinedAt;
  private String quarantinedBy;

  public Batch(UUID productUid, String batchNumber, LocalDate expiryDate, BigDecimal unitCost) {
    this.productUid = productUid;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.unitCost = Amounts.money(unitCost);
  }

  /**
   * Takes in {@code quantity} more units bought at {@code cost} while {@code onHand} units of the
   * batch are held across the branches: the batch's cost becomes the weighted average of the two,
   * to the cent. Returns how far the stock's value at the new cost differs from what it was worth
   * plus what was paid, which is the cent rounding of the average (a few cents either way); the
   * books post it as a stock adjustment so they keep matching the stock at cost. With nothing on
   * hand the batch simply takes the new cost.
   */
  public BigDecimal receive(long onHand, int quantity, BigDecimal cost) {
    BigDecimal received = Amounts.money(cost);
    if (onHand <= 0) {
      unitCost = received;
      return Amounts.ZERO;
    }
    if (received.compareTo(unitCost) == 0) {
      return Amounts.ZERO;
    }
    BigDecimal held = unitCost.multiply(BigDecimal.valueOf(onHand));
    BigDecimal paid = received.multiply(BigDecimal.valueOf(quantity));
    BigDecimal units = BigDecimal.valueOf(onHand + quantity);
    BigDecimal averaged = Amounts.money(held.add(paid).divide(units, 10, RoundingMode.HALF_UP));
    unitCost = averaged;
    return Amounts.money(averaged.multiply(units).subtract(held).subtract(paid));
  }

  /** Recalled: kept on hand but never sold, transferred or transformed until released. */
  public void quarantine(String reason, Instant at, String by) {
    this.quarantined = true;
    this.quarantineReason = reason;
    this.quarantinedAt = at;
    this.quarantinedBy = by;
  }

  public void release() {
    this.quarantined = false;
    this.quarantineReason = null;
    this.quarantinedAt = null;
    this.quarantinedBy = null;
  }

  public boolean expiredOn(LocalDate day) {
    return expiryDate != null && expiryDate.isBefore(day);
  }
}
