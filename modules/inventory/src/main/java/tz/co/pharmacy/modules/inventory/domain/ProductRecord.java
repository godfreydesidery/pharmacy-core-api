package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshot;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Inventory's snapshot of a catalogue product: what it needs to list, sort and value stock. */
@Getter
@Entity
@Table(schema = "inventory", name = "products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductRecord extends TenantEntity {

  private UUID productUid;
  private String name;
  private String genericName;
  private String sku;
  private String dosageForm;
  private String strength;
  private UUID categoryUid;
  private String categoryName;
  private int reorderLevel;
  private BigDecimal sellingPrice;
  private boolean active;
  private String productType = "MEDICINE";
  private String trackingMode = "BATCH_EXPIRY";
  private String unitOfMeasure = "PIECE";

  public ProductRecord(ProductSnapshot snapshot) {
    this.productUid = snapshot.productUid();
    apply(snapshot);
  }

  public void apply(ProductSnapshot snapshot) {
    this.name = snapshot.name();
    this.genericName = snapshot.genericName();
    this.sku = snapshot.sku();
    this.dosageForm = snapshot.dosageForm();
    this.strength = snapshot.strength();
    this.categoryUid = snapshot.categoryUid();
    this.categoryName = snapshot.categoryName();
    this.reorderLevel = Math.max(0, snapshot.reorderLevel());
    this.sellingPrice = Amounts.money(snapshot.sellingPrice());
    this.active = snapshot.active();
    if (snapshot.productType() != null) {
      this.productType = snapshot.productType();
    }
    if (snapshot.trackingMode() != null) {
      this.trackingMode = snapshot.trackingMode();
    }
    if (snapshot.unitOfMeasure() != null) {
      this.unitOfMeasure = snapshot.unitOfMeasure();
    }
  }

  /** Whether stock is counted unit by unit, by serial number. */
  public boolean serialTracked() {
    return "SERIAL".equals(trackingMode);
  }

  /** Whether each batch carries an expiry date. */
  public boolean expiryTracked() {
    return "BATCH_EXPIRY".equals(trackingMode);
  }

  /** Whether stock is one implicit batch, counted by quantity alone. */
  public boolean quantityOnly() {
    return "QUANTITY".equals(trackingMode);
  }
}
