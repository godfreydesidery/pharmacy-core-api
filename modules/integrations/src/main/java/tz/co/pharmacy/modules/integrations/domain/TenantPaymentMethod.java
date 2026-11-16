package tz.co.pharmacy.modules.integrations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A registered payment method a pharmacy accepts, with its own label and merchant details. */
@Getter
@Entity
@Table(schema = "integrations", name = "tenant_payment_methods")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantPaymentMethod extends TenantEntity {

  private String methodCode;
  private String label;
  private String accountReference;
  private String sealedCredentials;
  private boolean enabled = true;

  public TenantPaymentMethod(String methodCode) {
    this.methodCode = methodCode;
  }

  public void change(String label, String accountReference, boolean enabled) {
    this.label = label;
    this.accountReference = accountReference;
    this.enabled = enabled;
  }
}
