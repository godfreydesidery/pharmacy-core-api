package tz.co.pharmacy.modules.inventory.ports.inbound;

/**
 * BREAK_BULK: a pack opened into its units (a box into strips). REPACK: bulk stock put into the
 * pharmacy's own labelled packs. COMPOUND: a preparation made from ingredients.
 */
public enum TransformationType {
  BREAK_BULK,
  REPACK,
  COMPOUND
}
