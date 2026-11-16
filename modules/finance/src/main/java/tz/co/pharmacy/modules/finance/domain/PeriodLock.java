package tz.co.pharmacy.modules.finance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** The date through which the tenant's books are closed; null when none is. */
@Getter
@Entity
@Table(schema = "finance", name = "period_locks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PeriodLock extends TenantEntity {

  private LocalDate lockedThrough;

  public PeriodLock(LocalDate lockedThrough) {
    this.lockedThrough = lockedThrough;
  }

  public void lockThrough(LocalDate date) {
    this.lockedThrough = date;
  }
}
