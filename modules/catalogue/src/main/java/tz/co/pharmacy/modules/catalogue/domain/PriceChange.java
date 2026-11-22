package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * One change of a medicine's price: its Normal Sale price ({@code priceListUid} null) or its price
 * in a pricing plan. Kept for good: the table refuses updates and deletes.
 */
@Getter
@Entity
@Table(schema = "catalogue", name = "price_changes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceChange extends TenantEntity {

  /** Where a change came from. */
  public enum Source {
    /** The pricing plans page. */
    MANUAL,
    /** An Excel or CSV import. */
    IMPORT,
    /** The product form (its selling price). */
    PRODUCT,
    /** A bulk update (Normal ± %, rounded). */
    BULK
  }

  @Column(nullable = false)
  private UUID productUid;

  @Column(nullable = false)
  private String productName;

  private UUID priceListUid;

  @Column(nullable = false)
  private String planName;

  @Column(nullable = false)
  private int minQuantity;

  private BigDecimal oldPrice;

  private BigDecimal newPrice;

  @Column(nullable = false)
  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  private Source source;

  public PriceChange(
      UUID productUid,
      String productName,
      UUID priceListUid,
      String planName,
      int minQuantity,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      Source source) {
    this.productUid = productUid;
    this.productName = productName;
    this.priceListUid = priceListUid;
    this.planName = planName;
    this.minQuantity = minQuantity;
    this.oldPrice = oldPrice;
    this.newPrice = newPrice;
    this.source = source;
  }
}
