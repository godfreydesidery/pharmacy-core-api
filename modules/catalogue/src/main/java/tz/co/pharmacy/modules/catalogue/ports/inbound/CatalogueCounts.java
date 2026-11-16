package tz.co.pharmacy.modules.catalogue.ports.inbound;

/** Sizes of the current tenant's catalogue, for subscription limits. */
public interface CatalogueCounts {

  /** Products of the current tenant, active or not. */
  long productCount();
}
