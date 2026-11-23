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
   * Takes in {@code quantity} more units bought at {@code cost} each while {@code onHand} units of
   * the batch are held (on the shelves of every branch and in transit between them): the batch's
   * cost becomes the weighted average of the two, to the cent. Returns how far the stock's value at
   * the new cost differs from what it was worth plus what was paid, which is the cent rounding of
   * the average or of a cost given to more than cents (a foreign price at the receipt's rate); the
   * books post it as a stock adjustment so they keep matching the stock at cost.
   */
  public BigDecimal receive(long onHand, int quantity, BigDecimal cost) {
    return receiveValue(onHand, quantity, cost.multiply(BigDecimal.valueOf(quantity)));
  }

  /**
   * As {@link #receive}, for units that came in worth {@code value} together (the cost of the
   * inputs of a break bulk or repack, the cost a return was booked at). With nothing held the batch
   * takes the value shared over the units.
   */
  public BigDecimal receiveValue(long onHand, int quantity, BigDecimal value) {
    long held = Math.max(onHand, 0);
    BigDecimal heldValue = unitCost.multiply(BigDecimal.valueOf(held));
    BigDecimal units = BigDecimal.valueOf(held + quantity);
    BigDecimal averaged =
        Amounts.money(heldValue.add(value).divide(units, 10, RoundingMode.HALF_UP));
    unitCost = averaged;
    return Amounts.money(averaged.multiply(units).subtract(heldValue).subtract(value));
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
