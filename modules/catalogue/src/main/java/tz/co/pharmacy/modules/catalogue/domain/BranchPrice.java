package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A product's retail price at one branch, replacing the catalogue price there. */
@Getter
@Entity
@Table(schema = "catalogue", name = "branch_prices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchPrice extends TenantEntity {

  private UUID productUid;
  private UUID branchUid;
  private BigDecimal price;

  public BranchPrice(UUID productUid, UUID branchUid, BigDecimal price) {
    this.productUid = productUid;
    this.branchUid = branchUid;
    this.price = price;
  }

  public void price(BigDecimal price) {
    this.price = price;
  }
}
