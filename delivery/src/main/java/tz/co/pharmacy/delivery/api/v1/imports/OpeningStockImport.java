package tz.co.pharmacy.delivery.api.v1.imports;

import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.optional;
import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.required;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.ReceiveStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;

/**
 * Opening stock at the current branch: one row per batch, the product named by its Code. Each row
 * adds stock as the Opening stock screen does; an empty unit cost takes the product's cost.
 */
@Component
class OpeningStockImport implements Importer<OpeningStockImport.Plan> {

  private final CatalogueService catalogue;
  private final StockService stock;

  OpeningStockImport(CatalogueService catalogue, StockService stock) {
    this.catalogue = catalogue;
    this.stock = stock;
  }

  @Override
  public String kind() {
    return "opening-stock";
  }

  private static final List<ImportColumn> COLUMNS =
      List.of(
          required(
                  "code",
                  "Code",
                  "Msimbo",
                  "The product's code, as on the Products list.",
                  "Msimbo wa bidhaa, kama kwenye orodha ya Bidhaa.")
              .about("productUid"),
          optional(
              "product",
              "Product",
              "Bidhaa",
              "The product's name, for your reference only; the Code decides.",
              "Jina la bidhaa, kwa marejeo tu; Msimbo ndio unaoamua."),
          optional(
                  "batch",
                  "Batch number",
                  "Namba ya bechi",
                  "The batch (lot) number; products tracked by batch need it.",
                  "Namba ya bechi; bidhaa zinazofuatiliwa kwa bechi zinaihitaji.")
              .about("batchNumber"),
          optional(
                  "expiry",
                  "Expiry date",
                  "Tarehe ya kuisha",
                  "Such as 2027-06-30 or 30/06/2027; medicines need it.",
                  "Kama 2027-06-30 au 30/06/2027; dawa zinaihitaji.")
              .about("expiryDate"),
          required(
              "quantity",
              "Quantity",
              "Idadi",
              "How many units are on hand, a whole number.",
              "Idadi ya vipande vilivyopo, namba kamili."),
          optional(
                  "unitCost",
                  "Unit cost",
                  "Gharama kwa kipande",
                  "What one unit cost; empty takes the product's cost.",
                  "Gharama ya kipande kimoja; tupu inachukua gharama ya bidhaa.")
              .about("unitCost"),
          optional(
                  "serials",
                  "Serial numbers",
                  "Namba za utambulisho",
                  "For equipment tracked by serial: the numbers, separated by commas.",
                  "Kwa vifaa vinavyofuatiliwa kwa namba: namba zenyewe, zikitenganishwa kwa"
                      + " koma.")
              .about("serialNumbers"));

  @Override
  public List<ImportColumn> columns() {
    return COLUMNS;
  }

  @Override
  public List<Object> example() {
    return Arrays.asList(
        "P00001", "Paracetamol 500mg Tablets", "PCM-2401", "2027-06-30", 120, 3500, "");
  }

  record Plan(String code, ReceiveStockCommand command) {}

  @Override
  public Batch<Plan> start() {
    return new Batch<>() {
      @Override
      public Plan read(ImportRow row) {
        return OpeningStockImport.this.read(row);
      }

      @Override
      public boolean creates(Plan plan) {
        return true;
      }

      @Override
      public List<Key> keys(Plan plan) {
        String batch = plan.command().batchNumber();
        return batch == null ? List.of() : List.of(new Key("batch", plan.code() + " " + batch));
      }

      @Override
      public void save(Plan plan) {
        stock.openingStock(plan.command());
      }
    };
  }

  private Plan read(ImportRow row) {
    String code = row.required("code");
    ProductView product = code == null ? null : catalogue.productByCode(code).orElse(null);
    if (code != null && product == null) {
      row.problem(
          "code",
          "product_not_found",
          "names no product of the pharmacy: " + code,
          "haitaji bidhaa iliyopo: " + code);
    }
    String batch = row.text("batch");
    LocalDate expiry = row.date("expiry");
    Integer quantity = row.text("quantity") == null ? null : row.whole("quantity");
    if (row.text("quantity") == null) {
      row.required("quantity");
    } else if (quantity != null && quantity <= 0) {
      row.problem(
          "quantity", "invalid_quantity", "must be more than zero", "lazima iwe zaidi ya sifuri");
    }
    BigDecimal unitCost = row.number("unitCost");
    String serials = row.text("serials");
    if (!row.ok()) {
      return null;
    }
    UUID productUid = product.uid();
    List<String> serialNumbers =
        serials == null
            ? null
            : Arrays.stream(serials.split("[,;\\n]+"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    return new Plan(
        product.sku(),
        new ReceiveStockCommand(
            productUid,
            batch,
            expiry,
            quantity,
            unitCost == null ? product.costPrice() : unitCost,
            MovementType.OPENING,
            "IMPORT",
            serialNumbers));
  }
}
