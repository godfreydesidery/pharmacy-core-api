package tz.co.pharmacy.modules.tenancy.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A branch (shop, warehouse) of the tenant; stock, sales and purchases happen at one. */
@Getter
@Entity
@Table(schema = "tenancy", name = "branches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Branch extends TenantEntity {

  private String code;
  private String name;
  private String address;
  private String phone;
  private String licenceNumber;
  private boolean headOffice;
  private boolean active;

  public Branch(
      String code,
      String name,
      String address,
      String phone,
      String licenceNumber,
      boolean headOffice) {
    this.code = code;
    this.name = name;
    this.address = address;
    this.phone = phone;
    this.licenceNumber = licenceNumber;
    this.headOffice = headOffice;
    this.active = true;
  }

  public void change(
      String code,
      String name,
      String address,
      String phone,
      String licenceNumber,
      boolean active) {
    this.code = code;
    this.name = name;
    this.address = address;
    this.phone = phone;
    this.licenceNumber = licenceNumber;
    this.active = active;
  }
}
