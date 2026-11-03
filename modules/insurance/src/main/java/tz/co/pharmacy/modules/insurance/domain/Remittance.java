package tz.co.pharmacy.modules.insurance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** {@code allocations}: "CLM-000001=1200.00;CLM-000002=300.00". */
@Getter
@Setter
@Entity
@Table(schema = "insurance", name = "remittances")
@NoArgsConstructor
public class Remittance extends TenantEntity {
  private String number;
  private UUID payerUid;

  @Column(precision = 14, scale = 2)
  private BigDecimal amount;

  private String reference;
  private LocalDate remittedOn;
  private String allocations;
}
