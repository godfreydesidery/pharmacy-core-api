package tz.co.pharmacy.modules.insurance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Setter
@Entity
@Table(schema = "insurance", name = "claim_batches")
@NoArgsConstructor
public class ClaimBatch extends TenantEntity {
  private String number;
  private UUID payerUid;
  private LocalDate periodFrom;
  private LocalDate periodTo;
  private String status = "SUBMITTED";
  private Instant submittedAt;
}
