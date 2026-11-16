package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A cashier's time at a till, from the opening float to the counted cash at close. */
@Getter
@Entity
@Table(schema = "sales", name = "shifts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shift extends TenantEntity {

  private UUID branchUid;
  private String cashier;
  private String cashierName;
  private String status;
  private Instant openedAt;
  private BigDecimal openingFloat;
  private Instant closedAt;
  private BigDecimal expectedCash;
  private BigDecimal countedCash;
  private BigDecimal variance;
  private String note;

  public Shift(
      UUID branchUid, String cashier, String cashierName, BigDecimal openingFloat, Instant at) {
    this.branchUid = branchUid;
    this.cashier = cashier;
    this.cashierName = cashierName;
    this.openingFloat = Amounts.money(openingFloat);
    this.openedAt = at;
    this.status = "OPEN";
  }

  public void close(BigDecimal expected, BigDecimal counted, String note, Instant at) {
    this.expectedCash = Amounts.money(expected);
    this.countedCash = Amounts.money(counted);
    this.variance = Amounts.money(counted.subtract(expected));
    this.note = note;
    this.closedAt = at;
    this.status = "CLOSED";
  }
}
