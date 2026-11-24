package tz.co.pharmacy.delivery.api.v1.imports;

import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.optional;
import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.required;
import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.values;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CategoryView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.DosageForm;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ManufacturerCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ManufacturerView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductType;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Storage;
import tz.co.pharmacy.modules.catalogue.ports.inbound.UnitOfMeasure;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;

/**
 * Products, matched on Code: a code the pharmacy has updates that product, a new code adds a
 * product with it, and an empty Code adds a product with the next automatic code (P00001). The
 * columns are the product export's, so a pharmacy can export, edit and import back. The category
 * must exist; a manufacturer that does not is added.
 */
@Component
class ProductImport implements Importer<ProductImport.Plan> {

  private final CatalogueService catalogue;

  ProductImport(CatalogueService catalogue) {
    this.catalogue = catalogue;
  }

  @Override
  public String kind() {
    return "products";
  }

  @Override
  public PlanLimit limit() {
    return PlanLimit.PRODUCTS;
  }

  private static final List<ImportColumn> COLUMNS =
      List.of(
          optional(
                  "code",
                  "Code",
                  "Msimbo",
                  "The product's code, unique in the pharmacy. A code the pharmacy already has"
                      + " updates that product; leave it empty to add a product with the next code"
                      + " (P00001).",
                  "Msimbo wa bidhaa, wa kipekee katika famasia. Msimbo uliopo unasasisha bidhaa"
                      + " hiyo; uache wazi kuongeza bidhaa yenye msimbo unaofuata (P00001).")
              .about("sku"),
          required("name", "Name", "Jina", "The product's name.", "Jina la bidhaa."),
          optional(
                  "type",
                  "Type",
                  "Aina",
                  "One of " + values(ProductType.class) + ". Empty means MEDICINE.",
                  "Mojawapo ya " + values(ProductType.class) + ". Tupu ni MEDICINE.")
              .about("productType", "trackingMode"),
          optional(
              "genericName",
              "Generic name",
              "Jina la kisayansi",
              "The generic (INN) name; a medicine needs it.",
              "Jina la kisayansi (INN); dawa inalihitaji."),
          optional("brand", "Brand", "Chapa", "The brand, if any.", "Chapa, kama ipo."),
          optional(
                  "gtin",
                  "Barcode (GTIN)",
                  "Msimbopau (GTIN)",
                  "The barcode (GTIN): 8, 12, 13 or 14 digits ending in a correct check digit."
                      + " Format the column as Text so Excel keeps leading zeros.",
                  "Msimbopau (GTIN): tarakimu 8, 12, 13 au 14 zinazoishia na tarakimu hakiki"
                      + " sahihi. Weka safu kuwa Text ili Excel isifute sifuri za mwanzo.")
              .about("gtin"),
          optional(
              "atcCode",
              "ATC code",
              "Msimbo wa ATC",
              "The WHO ATC code of a medicine, such as N02BE01.",
              "Msimbo wa WHO ATC wa dawa, kama N02BE01."),
          optional(
                  "category",
                  "Category",
                  "Kundi",
                  "The name of a category the pharmacy has (add new ones under Categories"
                      + " first).",
                  "Jina la kundi lililopo (ongeza mapya kwenye Makundi kwanza).")
              .about("categoryUid"),
          optional(
                  "manufacturer",
                  "Manufacturer",
                  "Mtengenezaji",
                  "The manufacturer's name; a new name is added.",
                  "Jina la mtengenezaji; jina jipya litaongezwa.")
              .about("manufacturerUid"),
          optional(
              "dosageForm",
              "Dosage form",
              "Umbo la dawa",
              "One of " + values(DosageForm.class) + ". A medicine needs it.",
              "Mojawapo ya " + values(DosageForm.class) + ". Dawa inalihitaji."),
          optional("strength", "Strength", "Nguvu", "Such as 500mg.", "Kama 500mg."),
          optional("packSize", "Pack size", "Ukubwa wa pakiti", "Such as 10x10.", "Kama 10x10."),
          optional(
                  "schedule",
                  "Dispensing class",
                  "Aina ya utoaji",
                  "One of " + values(Schedule.class) + ". Empty means OTC.",
                  "Mojawapo ya " + values(Schedule.class) + ". Tupu ni OTC.")
              .about("schedule"),
          optional(
                  "unit",
                  "Unit",
                  "Kipimo",
                  "One of " + values(UnitOfMeasure.class) + ". Empty means PIECE.",
                  "Mojawapo ya " + values(UnitOfMeasure.class) + ". Tupu ni PIECE.")
              .about("unitOfMeasure"),
          optional("model", "Model", "Modeli", "Equipment's model.", "Modeli ya kifaa."),
          optional(
              "warrantyMonths", "Warranty (months)", "Dhamana (miezi)", "0 to 120.", "0 hadi 120."),
          required("cost", "Cost", "Gharama", "The buying price.", "Bei ya kununua.")
              .about("costPrice"),
          required("price", "Price", "Bei", "The selling price.", "Bei ya kuuza.")
              .about("sellingPrice"),
          optional(
                  "vat",
                  "VAT %",
                  "VAT %",
                  "The VAT rate, such as 18 or 0.",
                  "Kiwango cha VAT, kama 18 au 0.")
              .about("taxRatePercent"),
          optional(
              "reorderLevel",
              "Reorder level",
              "Kiwango cha kuagiza",
              "Order more at or below this quantity.",
              "Agiza zaidi idadi ikifika hapa au chini."),
          optional(
              "storage",
              "Storage",
              "Uhifadhi",
              "One of " + values(Storage.class) + ". Empty means ROOM_TEMPERATURE.",
              "Mojawapo ya " + values(Storage.class) + ". Tupu ni ROOM_TEMPERATURE."),
          optional(
              "active",
              "Active",
              "Inatumika",
              "Yes or No. Empty means Yes.",
              "Ndiyo au Hapana. Tupu ni Ndiyo."));

  @Override
  public List<ImportColumn> columns() {
    return COLUMNS;
  }

  @Override
  public List<Object> example() {
    return Arrays.asList(
        "",
        "Paracetamol 500mg Tablets",
        "MEDICINE",
        "Paracetamol",
        "Panadol",
        "",
        "N02BE01",
        "",
        "Shelys Pharmaceuticals",
        "TABLET",
        "500mg",
        "10x10",
        "OTC",
        "BOX",
        "",
        null,
        3500,
        5000,
        0,
        10,
        "ROOM_TEMPERATURE",
        "Yes");
  }

  /** A row's change: the product it updates (or null), the product, and how to finish it. */
  record Plan(
      String code,
      ProductView existing,
      ProductCommand command,
      String newManufacturer,
      boolean active) {}

  @Override
  public Batch<Plan> start() {
    Map<String, UUID> categories = new HashMap<>();
    for (CategoryView category : catalogue.categories()) {
      categories.put(key(category.name()), category.uid());
    }
    Map<String, UUID> manufacturers = new HashMap<>();
    for (ManufacturerView manufacturer : catalogue.manufacturers()) {
      manufacturers.put(key(manufacturer.name()), manufacturer.uid());
    }
    return new Batch<>() {
      @Override
      public Plan read(ImportRow row) {
        return ProductImport.this.read(row, categories, manufacturers);
      }

      @Override
      public boolean creates(Plan plan) {
        return plan.existing() == null;
      }

      @Override
      public List<Key> keys(Plan plan) {
        List<Key> keys = new ArrayList<>();
        keys.add(new Key("code", plan.code()));
        keys.add(new Key("gtin", plan.command().gtin()));
        return keys;
      }

      @Override
      public void save(Plan plan) {
        ProductImport.this.save(plan);
      }
    };
  }

  private Plan read(ImportRow row, Map<String, UUID> categories, Map<String, UUID> manufacturers) {
    String code = row.text("code");
    ProductView existing = code == null ? null : catalogue.productByCode(code).orElse(null);
    ProductView was = existing;

    String name = row.required("name");
    ProductType type =
        row.pick("type", was, ProductView::productType, r -> r.choice("type", ProductType.class));
    String genericName =
        row.pick("genericName", was, ProductView::genericName, r -> r.text("genericName"));
    String brand = row.pick("brand", was, ProductView::brand, r -> r.text("brand"));
    String gtin = row.pick("gtin", was, ProductView::gtin, r -> r.text("gtin"));
    String atc = row.pick("atcCode", was, ProductView::atcCode, r -> r.text("atcCode"));
    UUID categoryUid = was == null ? null : was.categoryUid();
    if (row.has("category")) {
      String category = row.text("category");
      categoryUid = category == null ? null : categories.get(key(category));
      if (category != null && categoryUid == null) {
        row.problem(
            "category",
            "category_not_found",
            "names no category of the pharmacy: " + category,
            "halitaji kundi lililopo: " + category);
      }
    }
    UUID manufacturerUid = was == null ? null : was.manufacturerUid();
    String newManufacturer = null;
    if (row.has("manufacturer")) {
      String manufacturer = row.text("manufacturer");
      manufacturerUid = manufacturer == null ? null : manufacturers.get(key(manufacturer));
      if (manufacturer != null && manufacturerUid == null) {
        newManufacturer = manufacturer;
      }
    }
    DosageForm form =
        row.pick(
            "dosageForm",
            was,
            ProductView::dosageForm,
            r -> r.choice("dosageForm", DosageForm.class));
    String strength = row.pick("strength", was, ProductView::strength, r -> r.text("strength"));
    String packSize = row.pick("packSize", was, ProductView::packSize, r -> r.text("packSize"));
    Schedule schedule =
        row.pick("schedule", was, ProductView::schedule, r -> r.choice("schedule", Schedule.class));
    UnitOfMeasure unit =
        row.pick(
            "unit", was, ProductView::unitOfMeasure, r -> r.choice("unit", UnitOfMeasure.class));
    String model = row.pick("model", was, ProductView::model, r -> r.text("model"));
    Integer warranty =
        row.pick(
            "warrantyMonths", was, ProductView::warrantyMonths, r -> r.whole("warrantyMonths"));
    BigDecimal cost = amount(row, "cost");
    BigDecimal price = amount(row, "price");
    BigDecimal vat = row.pick("vat", was, ProductView::taxRatePercent, r -> r.number("vat"));
    Integer reorder =
        row.pick("reorderLevel", was, ProductView::reorderLevel, r -> r.whole("reorderLevel"));
    Storage storage =
        row.pick("storage", was, ProductView::storage, r -> r.choice("storage", Storage.class));
    // Empty means Yes; with no Active column a product keeps what it has.
    Boolean active = row.yes("active");
    if (active == null) {
      active = row.has("active") || was == null || was.active();
    }
    if (!row.ok()) {
      return null;
    }
    ProductCommand command =
        new ProductCommand(
            name,
            genericName,
            was == null ? code : was.sku(),
            gtin,
            atc,
            categoryUid,
            manufacturerUid,
            form,
            strength,
            packSize,
            schedule,
            price,
            cost,
            vat,
            reorder,
            storage,
            type,
            was == null ? null : was.trackingMode(),
            unit,
            brand,
            model,
            warranty,
            was == null ? null : was.requiresSerialAtSale());
    return new Plan(code, existing, command, newManufacturer, active);
  }

  /** A required amount: empty is a problem. */
  private static BigDecimal amount(ImportRow row, String key) {
    if (row.text(key) == null) {
      row.required(key);
      return null;
    }
    return row.number(key);
  }

  private void save(Plan plan) {
    ProductCommand command = plan.command();
    if (plan.newManufacturer() != null) {
      command = withManufacturer(command, manufacturer(plan.newManufacturer()));
    }
    if (plan.existing() == null) {
      ProductView created = catalogue.createProduct(command);
      if (!plan.active()) {
        catalogue.changeProduct(created.uid(), command, false, created.version());
      }
    } else {
      catalogue.changeProduct(
          plan.existing().uid(), command, plan.active(), plan.existing().version());
    }
  }

  /**
   * The manufacturer of that name, added when the pharmacy has none: looked up afresh, so a name
   * added by an earlier row of the same import is found, not added twice.
   */
  private UUID manufacturer(String name) {
    return catalogue.manufacturers().stream()
        .filter(m -> key(m.name()).equals(key(name)))
        .map(ManufacturerView::uid)
        .findFirst()
        .orElseGet(() -> catalogue.createManufacturer(new ManufacturerCommand(name, null)).uid());
  }

  private static ProductCommand withManufacturer(ProductCommand c, UUID manufacturerUid) {
    return new ProductCommand(
        c.name(),
        c.genericName(),
        c.sku(),
        c.gtin(),
        c.atcCode(),
        c.categoryUid(),
        manufacturerUid,
        c.dosageForm(),
        c.strength(),
        c.packSize(),
        c.schedule(),
        c.sellingPrice(),
        c.costPrice(),
        c.taxRatePercent(),
        c.reorderLevel(),
        c.storage(),
        c.productType(),
        c.trackingMode(),
        c.unitOfMeasure(),
        c.brand(),
        c.model(),
        c.warrantyMonths(),
        c.requiresSerialAtSale());
  }

  private static String key(String name) {
    return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
  }
}
