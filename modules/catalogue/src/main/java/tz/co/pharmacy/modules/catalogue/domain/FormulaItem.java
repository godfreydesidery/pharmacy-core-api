package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** How much of an ingredient one unit of a compounded product takes. */
@Getter
@Entity
@Table(schema = "catalogue", name = "formula_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FormulaItem extends TenantEntity {

  private UUID productUid;
  private UUID ingredientUid;
  private BigDecimal quantity;

  public FormulaItem(UUID productUid, UUID ingredientUid, BigDecimal quantity) {
    this.productUid = productUid;
    this.ingredientUid = ingredientUid;
    this.quantity = quantity;
  }
}
