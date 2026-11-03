package tz.co.pharmacy.modules.insurance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Setter
@Entity
@Table(schema = "insurance", name = "payers")
@NoArgsConstructor
public class Payer extends TenantEntity {
  private String code;
  private String name;
  private String contact;
  private String email;
  private int paymentTermsDays = 30;
  private boolean requiresPreauth;
  private boolean active = true;
}
