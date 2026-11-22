package tz.co.pharmacy.delivery.api.v1.imports;

import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.optional;
import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.required;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.ItemCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.ItemView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.PriceListCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.PriceListView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The items of one price list: a product (by Code), the quantity from which the price applies and
 * the price. A product and minimum quantity already on the list get the new price; others are
 * added; items the file does not name stay as they are. The list is saved whole, once, after every
 * row is read. Its template holds the list's current items, so it can be edited and imported back.
 */
@Component
class PriceListImport {

  private final PricingService pricing;
  private final CatalogueService catalogue;

  PriceListImport(PricingService pricing, CatalogueService catalogue) {
    this.pricing = pricing;
    this.catalogue = catalogue;
  }

  private static final List<ImportColumn> COLUMNS =
      List.of(
          required(
              "code",
              "Code",
              "Msimbo",
              "The product's code, as on the Products list.",
              "Msimbo wa bidhaa, kama kwenye orodha ya Bidhaa."),
          optional(
              "product",
              "Product",
              "Bidhaa",
              "The product's name, for your reference only; the Code decides.",
              "Jina la bidhaa, kwa marejeo tu; Msimbo ndio unaoamua."),
          optional(
              "minQuantity",
              "Min quantity",
              "Idadi ya chini",
              "The price applies from this quantity (a quantity break); empty means 1.",
              "Bei inaanza kutumika kuanzia idadi hii; tupu ni 1."),
          required(
              "price",
              "Price",
              "Bei",
              "The unit price on this list.",
              "Bei ya kipande kwenye" + " orodha hii."));

  /** The import of the list {@code uid}; 404 price_list_not_found when there is none. */
  Importer<Plan> forList(UUID uid) {
    PriceListView list =
        pricing.priceLists().stream()
            .filter(candidate -> candidate.uid().equals(uid))
            .findFirst()
            .orElseThrow(() -> Refusal.notFound("price_list_not_found", "No such price list."));
    return new ListImport(list);
  }

  /** One row: the product, the quantity break and the price. */
  record Plan(String code, UUID productUid, int minQuantity, BigDecimal price, boolean onList) {}

  private final class ListImport implements Importer<Plan> {

    private final PriceListView list;

    ListImport(PriceListView list) {
      this.list = list;
    }

    @Override
    public String kind() {
      return "price-list";
    }

    @Override
    public List<ImportColumn> columns() {
      return COLUMNS;
    }

    @Override
    public List<Object> example() {
      return Arrays.asList("P00001", "Paracetamol 500mg Tablets", 1, 4500);
    }

    /** The list's items now, each with its product's code; the example when it has none. */
    @Override
    public List<List<Object>> templateRows() {
      if (list.items().isEmpty()) {
        return List.of(example());
      }
      Map<UUID, String> codes = new LinkedHashMap<>();
      for (ProductView product :
          catalogue.products(list.items().stream().map(ItemView::productUid).toList())) {
        codes.put(product.uid(), product.sku());
      }
      List<List<Object>> rows = new ArrayList<>();
      for (ItemView item : list.items()) {
        rows.add(
            Arrays.asList(
                codes.get(item.productUid()),
                item.productName(),
                item.minQuantity(),
                item.price()));
      }
      return rows;
    }

    @Override
    public Batch<Plan> start() {
      Map<String, BigDecimal> current = new LinkedHashMap<>();
      for (ItemView item : list.items()) {
        current.put(key(item.productUid(), item.minQuantity()), item.price());
      }
      List<Plan> saved = new ArrayList<>();
      return new Batch<>() {
        @Override
        public Plan read(ImportRow row) {
          String code = row.required("code");
          ProductView product = code == null ? null : catalogue.productByCode(code).orElse(null);
          if (code != null && product == null) {
            row.problem(
                "code",
                "product_not_found",
                "names no product of the pharmacy: " + code,
                "haitaji bidhaa iliyopo: " + code);
          }
          Integer min = row.whole("minQuantity");
          if (min != null && min < 1) {
            row.problem(
                "minQuantity", "invalid_quantity", "must be 1 or more", "lazima iwe 1 au zaidi");
          }
          BigDecimal price = null;
          if (row.text("price") == null) {
            row.required("price");
          } else {
            price = row.number("price");
            if (price != null && price.signum() < 0) {
              row.problem("price", "invalid_price", "cannot be negative", "haiwezi kuwa hasi");
            }
          }
          if (!row.ok()) {
            return null;
          }
          int from = min == null ? 1 : min;
          return new Plan(
              product.sku(),
              product.uid(),
              from,
              price,
              current.containsKey(key(product.uid(), from)));
        }

        @Override
        public boolean creates(Plan plan) {
          return !plan.onList();
        }

        @Override
        public List<Key> keys(Plan plan) {
          return List.of(new Key("code", plan.code() + " × " + plan.minQuantity()));
        }

        @Override
        public void save(Plan plan) {
          saved.add(plan);
        }

        /** The list with its items: changed ones repriced, new ones added, the rest kept. */
        @Override
        public void finish() {
          Map<String, ItemCommand> items = new LinkedHashMap<>();
          for (ItemView item : list.items()) {
            items.put(
                key(item.productUid(), item.minQuantity()),
                new ItemCommand(item.productUid(), item.minQuantity(), item.price()));
          }
          for (Plan plan : saved) {
            items.put(
                key(plan.productUid(), plan.minQuantity()),
                new ItemCommand(plan.productUid(), plan.minQuantity(), plan.price()));
          }
          pricing.savePriceList(
              list.uid(),
              new PriceListCommand(
                  list.name(),
                  list.customerType(),
                  list.customerUid(),
                  list.customerName(),
                  list.adjustmentPercent(),
                  list.validFrom(),
                  list.validTo(),
                  list.active(),
                  new ArrayList<>(items.values())));
        }
      };
    }
  }

  private static String key(UUID productUid, int minQuantity) {
    return productUid + ":" + minQuantity;
  }
}
