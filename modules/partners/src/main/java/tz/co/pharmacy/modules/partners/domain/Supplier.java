package tz.co.pharmacy.modules.partners.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Entity
@Table(schema = "partners", name = "suppliers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier extends TenantEntity {

  @Column(updatable = false)
  private String code;

  private String name;
  private String contactPerson;
  private String phone;
  private String email;
  private String tin;

  @Enumerated(EnumType.STRING)
  private PaymentTerms paymentTerms;

  private String address;
  private boolean active = true;

  public Supplier(String code, Details details) {
    this.code = code;
    change(details);
  }

  public void change(Details d) {
    this.name = d.name();
    this.contactPerson = d.contactPerson();
    this.phone = d.phone();
    this.email = d.email();
    this.tin = d.tin();
    this.paymentTerms = d.paymentTerms();
    this.address = d.address();
    this.active = d.active();
  }

  public record Details(
      String name,
      String contactPerson,
      String phone,
      String email,
      String tin,
      PaymentTerms paymentTerms,
      String address,
      boolean active) {}
}
