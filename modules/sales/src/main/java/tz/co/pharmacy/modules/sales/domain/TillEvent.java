package tz.co.pharmacy.modules.sales.domain;

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

/** A sale that did not happen, as the till reported it (FRD-20). */
@Getter
@Entity
@Table(schema = "sales", name = "till_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TillEvent extends TenantEntity {

  private UUID branchUid;
  private UUID shiftUid;
  private String kind;
  private int lines;
  private BigDecimal value;
  private String reason;
  private String cashierName;
  private LocalDate occurredOn;

  public TillEvent(
      UUID branchUid,
      UUID shiftUid,
      String kind,
      int lines,
      BigDecimal value,
      String reason,
      String cashierName,
      LocalDate occurredOn) {
    this.branchUid = branchUid;
    this.shiftUid = shiftUid;
    this.kind = kind;
    this.lines = lines;
    this.value = Amounts.money(value);
    this.reason = reason;
    this.cashierName = cashierName;
    this.occurredOn = occurredOn;
  }
}
