package tz.co.pharmacy.modules.catalogue.ports.inbound;

/** Whether a product is sold over the counter or needs a prescription. */
public enum Schedule {
  OTC,
  PRESCRIPTION,
  CONTROLLED
}
