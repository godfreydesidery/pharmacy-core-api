package tz.co.pharmacy.delivery.api.v1.imports;

import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.optional;
import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.required;
import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.values;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierCommand;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Suppliers, matched on Code: a code the pharmacy has updates that supplier, an empty Code adds a
 * supplier with the next code (SUP-000001). The columns are the supplier export's.
 */
@Component
class SupplierImport implements Importer<SupplierImport.Plan> {

  private final SupplierService suppliers;

  SupplierImport(SupplierService suppliers) {
    this.suppliers = suppliers;
  }

  @Override
  public String kind() {
    return "suppliers";
  }

  private static final List<ImportColumn> COLUMNS =
      List.of(
          optional(
              "code",
              "Code",
              "Msimbo",
              "A supplier's code (such as SUP-000001) updates that supplier; leave it empty to add"
                  + " a supplier.",
              "Msimbo wa msambazaji (kama SUP-000001) unasasisha msambazaji huyo; uache wazi"
                  + " kuongeza mpya."),
          required("name", "Name", "Jina", "The supplier's name.", "Jina la msambazaji."),
          optional(
              "contactPerson",
              "Contact person",
              "Mtu wa mawasiliano",
              "Whom to call.",
              "Mtu wa kupigiwa simu."),
          optional(
              "phone",
              "Phone",
              "Simu",
              "Such as +255712345678 or 0712345678.",
              "Kama +255712345678 au 0712345678."),
          optional("email", "Email", "Barua pepe", "An email address.", "Anwani ya barua pepe."),
          optional("tin", "TIN", "TIN", "The TRA taxpayer number.", "Namba ya mlipakodi ya TRA."),
          optional("address", "Address", "Anwani", "Where they are.", "Mahali walipo."),
          optional(
              "paymentTerms",
              "Payment terms",
              "Masharti ya malipo",
              "One of " + values(PaymentTerms.class) + ".",
              "Mojawapo ya " + values(PaymentTerms.class) + "."),
          optional(
              "leadTimeDays",
              "Lead time (days)",
              "Muda wa kuleta (siku)",
              "Days from order to delivery.",
              "Siku kutoka oda hadi kuletewa."),
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
        "Kariakoo Pharma Distributors",
        "Juma Ally",
        "+255713000111",
        "orders@example.co.tz",
        "123-456-789",
        "Kariakoo, Dar es Salaam",
        "NET_30",
        3,
        "Yes");
  }

  record Plan(String code, SupplierView existing, SupplierCommand command) {}

  @Override
  public Batch<Plan> start() {
    return new Batch<>() {
      @Override
      public Plan read(ImportRow row) {
        return SupplierImport.this.read(row);
      }

      @Override
      public boolean creates(Plan plan) {
        return plan.existing() == null;
      }

      @Override
      public List<Key> keys(Plan plan) {
        return List.of(new Key("code", plan.code()));
      }

      @Override
      public void save(Plan plan) {
        if (plan.existing() == null) {
          suppliers.create(plan.command());
        } else {
          suppliers.change(plan.existing().uid(), plan.command());
        }
      }
    };
  }

  private Plan read(ImportRow row) {
    String code = row.text("code");
    SupplierView was = null;
    if (code != null) {
      was = byCode(code).orElse(null);
      if (was == null) {
        row.problem(
            "code",
            "supplier_not_found",
            "names no supplier of the pharmacy: "
                + code
                + ". Leave it empty to add a new supplier.",
            "haitaji msambazaji aliyepo: " + code + ". Uache wazi kuongeza mpya.");
      }
    }
    String name = row.required("name");
    String contact =
        row.pick("contactPerson", was, SupplierView::contactPerson, r -> r.text("contactPerson"));
    String phone = row.pick("phone", was, SupplierView::phone, r -> r.text("phone"));
    String email = row.pick("email", was, SupplierView::email, r -> r.text("email"));
    String tin = row.pick("tin", was, SupplierView::tin, r -> r.text("tin"));
    String address = row.pick("address", was, SupplierView::address, r -> r.text("address"));
    PaymentTerms terms =
        row.pick(
            "paymentTerms",
            was,
            SupplierView::paymentTerms,
            r -> r.choice("paymentTerms", PaymentTerms.class));
    Integer leadTime =
        row.pick("leadTimeDays", was, SupplierView::leadTimeDays, r -> r.whole("leadTimeDays"));
    Boolean active = row.yes("active");
    if (active == null) {
      active = row.has("active") || was == null || was.active();
    }
    if (!row.ok()) {
      return null;
    }
    return new Plan(
        code,
        was,
        new SupplierCommand(name, contact, phone, email, tin, terms, address, active, leadTime));
  }

  private Optional<SupplierView> byCode(String code) {
    return suppliers.search(code, PageQuery.of(null, PageQuery.MAX_LIMIT)).items().stream()
        .filter(supplier -> code.equalsIgnoreCase(supplier.code()))
        .findFirst();
  }
}
