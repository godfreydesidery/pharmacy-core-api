package tz.co.pharmacy.platform.composition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesProducts;

/** Binds sales' read of the catalogue: a product's dispensing class now. */
@Component
@RequiredArgsConstructor
class SalesProductsAdapter implements SalesProducts {

  private final CatalogueService catalogue;

  @Override
  public Map<UUID, String> schedules(Collection<UUID> productUids) {
    if (productUids == null || productUids.isEmpty()) {
      return Map.of();
    }
    return catalogue.products(List.copyOf(productUids)).stream()
        .collect(
            Collectors.toMap(
                ProductView::uid,
                p -> p.schedule() == null ? Schedule.OTC.name() : p.schedule().name(),
                (a, b) -> a));
  }
}
