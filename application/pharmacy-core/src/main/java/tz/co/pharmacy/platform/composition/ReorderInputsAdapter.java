package tz.co.pharmacy.platform.composition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService.PackLinkView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductFilter;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.modules.purchasing.ports.outbound.ReorderInputs;
import tz.co.pharmacy.modules.sales.ports.inbound.SalesReports;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Feeds reorder suggestions from catalogue, inventory, sales and partners; reads only. */
@Component
@RequiredArgsConstructor
class ReorderInputsAdapter implements ReorderInputs {

  private static final int DEFAULT_LEAD_TIME = 7;

  private final CatalogueService catalogue;
  private final PackagingService packaging;
  private final StockQueries stock;
  private final SalesReports sales;
  private final SupplierService suppliers;

  @Override
  public List<ReorderProduct> products() {
    Map<UUID, Integer> packs = new HashMap<>();
    for (PackLinkView link : packaging.packLinks()) {
      packs.put(link.unitProductUid(), link.unitsPerPack());
    }
    List<ReorderProduct> out = new ArrayList<>();
    ProductFilter active = new ProductFilter(null, null, Boolean.TRUE, null);
    String cursor = null;
    do {
      Page<ProductView> page =
          catalogue.products(active, new PageQuery(cursor, PageQuery.MAX_LIMIT));
      for (ProductView p : page.items()) {
        out.add(
            new ReorderProduct(
                p.uid(),
                p.name(),
                p.reorderLevel(),
                p.costPrice(),
                packs.getOrDefault(p.uid(), 1)));
      }
      cursor = page.items().isEmpty() ? null : page.nextCursor();
    } while (cursor != null);
    return out;
  }

  @Override
  public Map<UUID, Integer> onHand(Collection<UUID> productUids) {
    return productUids.isEmpty() ? Map.of() : stock.onHand(productUids);
  }

  @Override
  public Map<UUID, Integer> unitsSold(UUID branchUid, Instant from, Instant to) {
    return sales.unitsSold(branchUid, from, to);
  }

  @Override
  public int leadTimeDays(UUID supplierUid) {
    return suppliers.find(supplierUid).map(SupplierView::leadTimeDays).orElse(DEFAULT_LEAD_TIME);
  }
}
