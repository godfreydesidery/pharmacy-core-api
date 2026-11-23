package tz.co.pharmacy.modules.sales.ports.outbound;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** The products' dispensing classes now (catalogue), for lines sold before the class was kept. */
public interface SalesProducts {

  /** OTC, PRESCRIPTION or CONTROLLED per product; a product not found is left out. */
  Map<UUID, String> schedules(Collection<UUID> productUids);
}
