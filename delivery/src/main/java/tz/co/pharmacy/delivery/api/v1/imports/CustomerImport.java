package tz.co.pharmacy.delivery.api.v1.imports;

import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.optional;
import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.required;
import static tz.co.pharmacy.delivery.api.v1.imports.ImportColumn.values;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.delivery.api.v1.partners.CreditLimits;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerCommand;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerType;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerView;
import tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Customers, matched on Code: a code the pharmacy has updates that customer, an empty Code adds a
 * customer with the next code (CUS-000001). The columns are the customer export's.
 */
@Component
class CustomerImport implements Importer<CustomerImport.Plan> {

  private final CustomerService customers;

  CustomerImport(CustomerService customers) {
    this.customers = customers;
  }

  @Override
  public String kind() {
    return "customers";
  }

  private static final List<ImportColumn> COLUMNS =
      List.of(
          optional(
              "code",
              "Code",
              "Msimbo",
              "A customer's code (such as CUS-000001) updates that customer; leave it empty to add"
                  + " a customer.",
              "Msimbo wa mteja (kama CUS-000001) unasasisha mteja huyo; uache wazi kuongeza mteja"
                  + " mpya."),
          required("name", "Name", "Jina", "The customer's name.", "Jina la mteja."),
          optional(
              "type",
              "Type",
              "Aina",
              "One of " + values(CustomerType.class) + ". Empty means RETAIL.",
              "Mojawapo ya " + values(CustomerType.class) + ". Tupu ni RETAIL."),
          optional(
              "phone",
              "Phone",
              "Simu",
              "Such as +255712345678 or 0712345678.",
              "Kama +255712345678 au 0712345678."),
          optional("email", "Email", "Barua pepe", "An email address.", "Anwani ya barua pepe."),
          optional("address", "Address", "Anwani", "Where they are.", "Mahali walipo."),
          optional(
              "creditLimit",
              "Credit limit",
              "Kikomo cha mkopo",
              "The most they may owe; empty for none.",
              "Kiasi cha juu wanachoweza kudaiwa; tupu kama hakuna."),
          optional(
              "paymentTerms",
              "Payment terms",
              "Masharti ya malipo",
              "One of " + values(PaymentTerms.class) + ".",
              "Mojawapo ya " + values(PaymentTerms.class) + "."),
          optional("insurer", "Insurer", "Bima", "The insurer, such as NHIF.", "Bima, kama NHIF.")
              .about("insuranceProvider"),
          optional(
                  "insuranceNumber",
                  "Insurance number",
                  "Namba ya bima",
                  "The member number with the insurer.",
                  "Namba ya uanachama kwa bima.")
              .about("insuranceNumber"),
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
        "Mama Neema",
        "RETAIL",
        "+255712345678",
        "",
        "Sinza, Dar es Salaam",
        null,
        "COD",
        "",
        "",
        "Yes");
  }

  record Plan(String code, CustomerView existing, CustomerCommand command) {}

  @Override
  public Batch<Plan> start() {
    return new Batch<>() {
      @Override
      public Plan read(ImportRow row) {
        return CustomerImport.this.read(row);
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
          customers.create(plan.command());
        } else {
          customers.change(plan.existing().uid(), plan.command());
        }
      }
    };
  }

  private Plan read(ImportRow row) {
    String code = row.text("code");
    CustomerView was = null;
    if (code != null) {
      was = byCode(code).orElse(null);
      if (was == null) {
        row.problem(
            "code",
            "customer_not_found",
            "names no customer of the pharmacy: "
                + code
                + ". Leave it empty to add a new customer.",
            "haitaji mteja aliyepo: " + code + ". Uache wazi kuongeza mteja mpya.");
      }
    }
    String name = row.required("name");
    CustomerType type =
        row.pick("type", was, CustomerView::type, r -> r.choice("type", CustomerType.class));
    String phone = row.pick("phone", was, CustomerView::phone, r -> r.text("phone"));
    String email = row.pick("email", was, CustomerView::email, r -> r.text("email"));
    String address = row.pick("address", was, CustomerView::address, r -> r.text("address"));
    var creditLimit =
        row.pick("creditLimit", was, CustomerView::creditLimit, r -> r.number("creditLimit"));
    if (CreditLimits.changes(was == null ? null : was.creditLimit(), creditLimit)
        && !CreditLimits.mayChange()) {
      // A credit limit is a finance decision (FRD-17), in an import as on the customer form.
      row.problem(
          "creditLimit",
          CreditLimits.REASON,
          "needs the finance permission (finance.manage) to be set or changed; leave it as it"
              + " is.",
          "kinahitaji ruhusa ya fedha (finance.manage) kuwekwa au kubadilishwa; kiache kama"
              + " kilivyo.");
    }
    PaymentTerms terms =
        row.pick(
            "paymentTerms",
            was,
            CustomerView::paymentTerms,
            r -> r.choice("paymentTerms", PaymentTerms.class));
    String insurer =
        row.pick("insurer", was, CustomerView::insuranceProvider, r -> r.text("insurer"));
    String number =
        row.pick(
            "insuranceNumber", was, CustomerView::insuranceNumber, r -> r.text("insuranceNumber"));
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
        new CustomerCommand(
            name, phone, email, type, insurer, number, creditLimit, address, active, terms));
  }

  private Optional<CustomerView> byCode(String code) {
    return customers.search(code, null, PageQuery.of(null, PageQuery.MAX_LIMIT)).items().stream()
        .filter(customer -> code.equalsIgnoreCase(customer.code()))
        .findFirst();
  }
}
