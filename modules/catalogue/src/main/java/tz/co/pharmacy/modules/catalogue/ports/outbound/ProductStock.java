package tz.co.pharmacy.modules.catalogue.ports.outbound;

import java.util.UUID;

/** Whether stock of a product exists anywhere (inventory). */
public interface ProductStock {

  /** Units the tenant holds at any branch, expired ones and those in transit included. */
  long unitsHeld(UUID productUid);
}
