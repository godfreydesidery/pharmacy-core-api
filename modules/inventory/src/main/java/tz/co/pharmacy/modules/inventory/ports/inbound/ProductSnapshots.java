package tz.co.pharmacy.modules.inventory.ports.inbound;

/**
 * Keeps inventory's snapshot of a catalogue product current; composition calls it on
 * product.changed.
 */
public interface ProductSnapshots {

  void upsert(ProductSnapshot snapshot);
}
