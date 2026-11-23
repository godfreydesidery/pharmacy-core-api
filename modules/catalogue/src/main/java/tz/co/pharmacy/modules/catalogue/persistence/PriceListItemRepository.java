package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.catalogue.domain.PriceList;
import tz.co.pharmacy.modules.catalogue.domain.PriceListItem;

/**
 * A plan's prices read by product, never the whole plan (PERF-11). The unique index on {@code
 * (price_list_id, product_uid, min_quantity)} serves both queries. Row-level security limits them
 * to the current tenant.
 */
public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {

  /** The plan's prices of these products, every quantity break. */
  @Query("select i from PriceListItem i where i.priceList = :list and i.productUid in :products")
  List<PriceListItem> itemsOf(
      @Param("list") PriceList list, @Param("products") Collection<java.util.UUID> products);

  /** One product's prices in every plan. */
  @Query("select i from PriceListItem i where i.productUid = :product")
  List<PriceListItem> itemsOfProduct(@Param("product") java.util.UUID productUid);

  /** Per plan (its id): how many prices it holds and for how many products. */
  @Query(
      "select i.priceList.id, count(i), count(distinct i.productUid) from PriceListItem i"
          + " group by i.priceList.id")
  List<Object[]> counts();
}
