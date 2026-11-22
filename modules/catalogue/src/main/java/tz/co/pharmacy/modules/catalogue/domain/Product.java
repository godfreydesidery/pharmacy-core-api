package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.catalogue.ports.inbound.DosageForm;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductType;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Storage;
import tz.co.pharmacy.modules.catalogue.ports.inbound.TrackingMode;
import tz.co.pharmacy.modules.catalogue.ports.inbound.UnitOfMeasure;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A product the pharmacy sells, such as Paracetamol 500mg tablets, and its prices. */
@Getter
@Entity
@Table(schema = "catalogue", name = "products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends TenantEntity {

  private String name;
  private String genericName;
  private String sku;
  private String gtin;
  private String atcCode;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id")
  private Category category;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "manufacturer_id")
  private Manufacturer manufacturer;

  @Enumerated(EnumType.STRING)
  private DosageForm dosageForm;

  private String strength;
  private String packSize;

  @Enumerated(EnumType.STRING)
  private Schedule schedule;

  @Column(precision = 14, scale = 2)
  private BigDecimal sellingPrice;

  /** A new Normal Sale price, from the pricing plans page. */
  public void reprice(BigDecimal sellingPrice) {
    this.sellingPrice = sellingPrice;
  }

  @Column(precision = 14, scale = 2)
  private BigDecimal costPrice;

  @Column(precision = 5, scale = 2)
  private BigDecimal taxRatePercent;

  private int reorderLevel;

  @Enumerated(EnumType.STRING)
  private Storage storage;

  private boolean active = true;

  @Enumerated(EnumType.STRING)
  private ProductType productType = ProductType.MEDICINE;

  @Enumerated(EnumType.STRING)
  private TrackingMode trackingMode = TrackingMode.BATCH_EXPIRY;

  @Enumerated(EnumType.STRING)
  private UnitOfMeasure unitOfMeasure = UnitOfMeasure.PIECE;

  private String brand;
  private String model;
  private Integer warrantyMonths;
  private boolean requiresSerialAtSale;

  public Product(Details details) {
    change(details);
  }

  public void change(Details details) {
    this.name = details.name();
    this.genericName = details.genericName();
    this.sku = details.sku();
    this.gtin = details.gtin();
    this.atcCode = details.atcCode();
    this.category = details.category();
    this.manufacturer = details.manufacturer();
    this.dosageForm = details.dosageForm();
    this.strength = details.strength();
    this.packSize = details.packSize();
    this.schedule = details.schedule();
    this.sellingPrice = details.sellingPrice();
    this.costPrice = details.costPrice();
    this.taxRatePercent = details.taxRatePercent();
    this.reorderLevel = details.reorderLevel();
    this.storage = details.storage();
    this.productType = details.productType();
    this.trackingMode = details.trackingMode();
    this.unitOfMeasure = details.unitOfMeasure();
    this.brand = details.brand();
    this.model = details.model();
    this.warrantyMonths = details.warrantyMonths();
    this.requiresSerialAtSale = details.requiresSerialAtSale();
  }

  public void activate(boolean active) {
    this.active = active;
  }

  /** A product's validated fields. */
  public record Details(
      String name,
      String genericName,
      String sku,
      String gtin,
      String atcCode,
      Category category,
      Manufacturer manufacturer,
      DosageForm dosageForm,
      String strength,
      String packSize,
      Schedule schedule,
      BigDecimal sellingPrice,
      BigDecimal costPrice,
      BigDecimal taxRatePercent,
      int reorderLevel,
      Storage storage,
      ProductType productType,
      TrackingMode trackingMode,
      UnitOfMeasure unitOfMeasure,
      String brand,
      String model,
      Integer warrantyMonths,
      boolean requiresSerialAtSale) {}
}
