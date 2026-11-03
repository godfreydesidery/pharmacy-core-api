package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Entity
@Table(schema = "catalogue", name = "manufacturers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Manufacturer extends TenantEntity {

  private String name;
  private String country;

  public Manufacturer(String name, String country) {
    change(name, country);
  }

  public void change(String name, String country) {
    this.name = name;
    this.country = country;
  }
}
