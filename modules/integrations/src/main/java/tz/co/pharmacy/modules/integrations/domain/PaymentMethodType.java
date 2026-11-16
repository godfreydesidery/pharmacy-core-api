package tz.co.pharmacy.modules.integrations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.MutableEntity;

/**
 * A payment method the platform offers every pharmacy: its kind (how it is booked) and the adapter
 * that takes it (none, a typed reference, or a provider that pushes a request to the phone).
 */
@Getter
@Entity
@Table(schema = "integrations", name = "payment_method_types")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentMethodType extends MutableEntity {

  private String code;
  private String name;
  private String kind;
  private String adapter;
  private boolean requiresReference;
  private boolean enabled;
  private int sortOrder;

  public PaymentMethodType(String code) {
    this.code = code;
  }

  public void change(
      String name,
      String kind,
      String adapter,
      boolean requiresReference,
      boolean enabled,
      int sortOrder) {
    this.name = name;
    this.kind = kind;
    this.adapter = adapter;
    this.requiresReference = requiresReference;
    this.enabled = enabled;
    this.sortOrder = sortOrder;
  }
}
