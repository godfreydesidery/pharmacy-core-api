package tz.co.pharmacy.modules.insurance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tz.co.pharmacy.modules.insurance.ports.inbound.CopayType;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Exclusions are kept as comma-separated lists: product types and product uids. */
@Getter
@Setter
@Entity
@Table(schema = "insurance", name = "schemes")
@NoArgsConstructor
public class Scheme extends TenantEntity {
  private UUID payerUid;
  private String name;

  @Enumerated(EnumType.STRING)
  private CopayType copayType = CopayType.NONE;

  @Column(precision = 14, scale = 2)
  private BigDecimal copayValue = BigDecimal.ZERO;

  @Column(precision = 14, scale = 2)
  private BigDecimal annualLimit;

  private String excludedProductTypes = "";
  private String excludedProductUids = "";
  private boolean active = true;
}
