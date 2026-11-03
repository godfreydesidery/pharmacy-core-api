package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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

  public Batch(UUID productUid, String batchNumber, LocalDate expiryDate, BigDecimal unitCost) {
    this.productUid = productUid;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.unitCost = Amounts.money(unitCost);
  }

  public boolean expiredOn(LocalDate day) {
    return expiryDate != null && expiryDate.isBefore(day);
  }
}
