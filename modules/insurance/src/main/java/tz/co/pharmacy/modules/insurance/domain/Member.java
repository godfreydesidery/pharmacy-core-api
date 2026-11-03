package tz.co.pharmacy.modules.insurance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Setter
@Entity
@Table(schema = "insurance", name = "members")
@NoArgsConstructor
public class Member extends TenantEntity {
  private UUID customerUid;
  private String customerName;
  private UUID schemeUid;
  private String memberNumber;
  private String principalName;
  private LocalDate validFrom;
  private LocalDate validUntil;
  private boolean active = true;

  public boolean coversOn(LocalDate on) {
    return active
        && (validFrom == null || !on.isBefore(validFrom))
        && (validUntil == null || !on.isAfter(validUntil));
  }
}
