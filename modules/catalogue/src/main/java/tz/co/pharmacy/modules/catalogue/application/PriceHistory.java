package tz.co.pharmacy.modules.catalogue.application;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.domain.PriceChange;
import tz.co.pharmacy.modules.catalogue.persistence.PriceChangeRepository;

/** Writes the price history, in the caller's transaction; an unchanged price writes nothing. */
@Component
class PriceHistory {

  static final String NORMAL_SALE = "Normal Sale";

  private final PriceChangeRepository changes;

  PriceHistory(PriceChangeRepository changes) {
    this.changes = changes;
  }

  void record(
      UUID productUid,
      String productName,
      UUID priceListUid,
      String planName,
      int minQuantity,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      PriceChange.Source source) {
    if (oldPrice == null
        ? newPrice == null
        : newPrice != null && oldPrice.compareTo(newPrice) == 0) {
      return;
    }
    changes.save(
        new PriceChange(
            productUid,
            productName,
            priceListUid,
            planName == null ? NORMAL_SALE : planName,
            Math.max(1, minQuantity),
            oldPrice,
            newPrice,
            source));
  }
}
