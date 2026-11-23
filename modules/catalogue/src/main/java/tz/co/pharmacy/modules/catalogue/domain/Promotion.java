package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * A percentage off for a period, on one product, one category (and its sub-categories), or every
 * product; at one branch or all.
 */
@Getter
@Entity
@Table(schema = "catalogue", name = "promotions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Promotion extends TenantEntity {

  private String name;
  private BigDecimal percentOff;
  private UUID productUid;
  private UUID categoryUid;
  private UUID branchUid;
  private LocalDate validFrom;
  private LocalDate validTo;
  private boolean active = true;

  public Promotion(String name) {
    this.name = name;
  }

  /** Off for good use: a promotion is never deleted, so sales priced by it keep naming it. */
  public void switchOff() {
    this.active = false;
  }

  public void change(
      String name,
      BigDecimal percentOff,
      UUID productUid,
      UUID categoryUid,
      UUID branchUid,
      LocalDate validFrom,
      LocalDate validTo,
      boolean active) {
    this.name = name;
    this.percentOff = percentOff;
    this.productUid = productUid;
    this.categoryUid = categoryUid;
    this.branchUid = branchUid;
    this.validFrom = validFrom;
    this.validTo = validTo;
    this.active = active;
  }
}
