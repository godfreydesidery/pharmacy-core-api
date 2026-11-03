package tz.co.pharmacy.platform.composition;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.outbound.ProductStock;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;

/** Binds the catalogue's stock check to inventory. */
@Component
class ProductStockAdapter implements ProductStock {

  private final StockQueries stock;

  ProductStockAdapter(StockQueries stock) {
    this.stock = stock;
  }

  @Override
  public long unitsHeld(UUID productUid) {
    return stock.unitsHeld(productUid);
  }
}
