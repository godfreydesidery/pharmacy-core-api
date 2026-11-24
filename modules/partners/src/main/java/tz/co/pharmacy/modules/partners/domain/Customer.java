package tz.co.pharmacy.modules.partners.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerType;
import tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Entity
@Table(schema = "partners", name = "customers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer extends TenantEntity {

  @Column(updatable = false)
  private String code;

  private String name;
  private String phone;
  private String email;

  @Enumerated(EnumType.STRING)
  private CustomerType type;

  private String insuranceProvider;
  private String insuranceNumber;

  @Column(precision = 14, scale = 2)
  private BigDecimal creditLimit;

  private String address;
  private boolean active = true;

  @Enumerated(EnumType.STRING)
  private PaymentTerms paymentTerms = PaymentTerms.NET_30;

  /**
   * When the customer was anonymised (STD-5). Written only by the anonymisation's own SQL, which
   * leaves no copy of the personal values in the automatic audit trail.
   */
  @Column(insertable = false, updatable = false)
  private java.time.Instant anonymisedAt;

  public boolean isAnonymised() {
    return anonymisedAt != null;
  }

  public Customer(String code, Details details) {
    this.code = code;
    change(details);
  }

  public void change(Details d) {
    this.name = d.name();
    this.phone = d.phone();
    this.email = d.email();
    this.type = d.type();
    this.insuranceProvider = d.insuranceProvider();
    this.insuranceNumber = d.insuranceNumber();
    this.creditLimit = d.creditLimit();
    this.address = d.address();
    this.active = d.active();
    this.paymentTerms = d.paymentTerms();
  }

  public record Details(
      String name,
      String phone,
      String email,
      CustomerType type,
      String insuranceProvider,
      String insuranceNumber,
      BigDecimal creditLimit,
      String address,
      boolean active,
      PaymentTerms paymentTerms) {}
}
