package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A product's price on a list from {@code minQuantity} units a line. */
@Getter
@Entity
@Table(schema = "catalogue", name = "price_list_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceListItem extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "price_list_id")
  private PriceList priceList;

  private UUID productUid;
  private int minQuantity;
  private BigDecimal price;

  public PriceListItem(UUID productUid, int minQuantity, BigDecimal price) {
    this.productUid = productUid;
    this.minQuantity = minQuantity;
    this.price = price;
  }

  void attachTo(PriceList list) {
    this.priceList = list;
  }

  /** The product and minimum quantity, unique on a list. */
  String key() {
    return productUid + ":" + minQuantity;
  }

  void reprice(BigDecimal newPrice) {
    this.price = newPrice;
  }
}
