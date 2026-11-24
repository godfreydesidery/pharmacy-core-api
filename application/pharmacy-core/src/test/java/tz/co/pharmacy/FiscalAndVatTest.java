package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import tz.co.pharmacy.platform.persistence.jobs.JobRuns;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Fiscal documents and VAT (adversarial review STD-1, STD-2), with the simulated tax authority:
 *
 * <ul>
 *   <li>a sale's receipt is a fiscal document, PENDING with the sale and issued after it commits,
 *       with the authority's number, a verification code, a QR payload and the branch's counters;
 *   <li>a return gets a credit note; a branch's day closes with a Z report, by hand or by the job;
 *   <li>each product has a VAT category: a mixed sale charges VAT on the STANDARD lines only, and
 *       the VAT report splits the sales by category;
 *   <li>input VAT on a goods receipt and on an expense goes to 1150 VAT receivable;
 *   <li>a pharmacy that is not VAT-registered charges no VAT and books none;
 *   <li>the books stay right: the trial balance and the balance sheet balance, the daily balances
 *       agree with the journal, the stock at cost moves with the ledger's inventory.
 * </ul>
 */
@TestPropertySource(
    properties = {
      "pharmacy.integrations.fiscal.provider=simulator",
      "pharmacy.events.resubmit-every=PT1S",
      "pharmacy.events.resubmit-older-than=PT1S"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FiscalAndVatTest extends IntegrationTest {

  private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  @Autowired ApplicationContext context;
  @Autowired JobRuns jobRuns;

  private Api admin;
  private Api till;
  private String tenantUid;
  private String branchUid;
  private String lotion;
  private String paracetamol;
  private String export;

  /**
   * The stock at cost less the ledger's inventory once the opening stock is in: opening stock is
   * not posted (the opening balances are), so from then on the two move together.
   */
  private double openingGap;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "FV" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenantUid =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Fiscal VAT %s","legalName":"Fiscal VAT %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenantUid, null);
    branchUid =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenantUid, branchUid);
    admin
        .put(
            "/api/v1/settings",
            "{\"vatRegistered\":true,\"tin\":\"123-456-789\",\"vrn\":\"40-012345-a\"}")
        .expect(200);
    // 1,180 with VAT inside (18%): net 1,000, VAT 180.
    lotion = product("Body Lotion 200ml", "COSMETIC", 1180, "STANDARD");
    paracetamol = product("Paracetamol 500mg", "MEDICINE", 500, "EXEMPT");
    export = product("Export Pack ORS", "MEDICINE", 300, "ZERO_RATED");
    for (String product : List.of(lotion, paracetamol, export)) {
      till.post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"B-%s","expiryDate":"%s","quantity":50,"unitCost":200}
              """
                  .formatted(product, product.substring(0, 4), LocalDate.now().plusYears(2)))
          .expect(201);
    }
    openingGap = stockAtCost() - ledger("1200");
  }

  @Test
  @DisplayName("TIN and VRN are kept in their forms; a malformed one is refused")
  void registration() {
    Response settings = admin.get("/api/v1/settings").expect(200);
    assertThat(settings.read("$.data.vatRegistered", Boolean.class)).isTrue();
    assertThat(settings.string("$.data.tin")).isEqualTo("123456789");
    assertThat(settings.string("$.data.vrn")).isEqualTo("40-012345-A");
    assertThat(admin.put("/api/v1/settings", "{\"tin\":\"12345\"}").expect(422).string("$.reason"))
        .isEqualTo("invalid_tin");
    assertThat(admin.put("/api/v1/settings", "{\"vrn\":\"x\"}").expect(422).string("$.reason"))
        .isEqualTo("invalid_vrn");
  }

  @Test
  @DisplayName("a mixed sale charges VAT on its standard lines; its receipt is issued after commit")
  void mixedSaleAndItsReceipt() {
    Response sale = sell(lines(lotion, 1, paracetamol, 2, export, 1), 2480);
    String uid = sale.string("$.data.uid");
    assertThat(sale.read("$.data.total", Double.class)).isEqualTo(2480.0);
    assertThat(sale.read("$.data.taxTotal", Double.class)).isEqualTo(180.0);
    assertThat(sale.json().read("$.data.lines[*].taxCategory", List.class))
        .containsExactly("STANDARD", "EXEMPT", "ZERO_RATED");
    assertThat(sale.json().read("$.data.lines[*].tax", List.class)).hasSize(3);
    // Written PENDING with the sale; the authority is asked after it committed.
    assertThat(sale.read("$.data.fiscalDue", Boolean.class)).isTrue();
    assertThat(sale.string("$.data.fiscalDocuments[0].kind")).isEqualTo("RECEIPT");
    assertThat(sale.string("$.data.fiscalDocuments[0].status")).isEqualTo("PENDING");

    Response issued = issued(uid);
    String number = issued.string("$.data.fiscalReference");
    assertThat(number).startsWith("SIM-HQ-R");
    assertThat(issued.read("$.data.fiscalDue", Boolean.class)).isFalse();
    assertThat(issued.string("$.data.fiscalDocuments[0].receiptNumber")).isEqualTo(number);
    assertThat(issued.string("$.data.fiscalDocuments[0].verificationCode")).hasSize(10);
    assertThat(issued.string("$.data.fiscalDocuments[0].qrPayload"))
        .contains("TIN:123456789", "VRN:40-012345-A", "NO:" + number, "TOTAL:2480.00");
    assertThat(issued.string("$.data.fiscalDocuments[0].sellerTin")).isEqualTo("123456789");
    assertThat(issued.string("$.data.fiscalDocuments[0].issuedAt")).isNotNull();
    assertThat(issued.read("$.data.fiscalDocuments[0].attempts", Integer.class)).isEqualTo(1);
    int counter = issued.read("$.data.fiscalDocuments[0].globalCounter", Integer.class);
    assertThat(issued.read("$.data.fiscalDocuments[0].dailyCounter", Integer.class)).isEqualTo(1);
    assertThat(issued.read("$.data.fiscalDocuments[0].taxTotal", Double.class)).isEqualTo(180.0);

    // The next sale takes the branch's next counters.
    String next = sell(lines(paracetamol, 1), 500).string("$.data.uid");
    Response second = issued(next);
    assertThat(second.read("$.data.fiscalDocuments[0].globalCounter", Integer.class))
        .isEqualTo(counter + 1);
    assertThat(second.read("$.data.fiscalDocuments[0].dailyCounter", Integer.class)).isEqualTo(2);

    // Sales history shows the receipt's status.
    List<String> statuses =
        till.get("/api/v1/sales").expect(200).json().read("$.data.items[*].fiscalStatus");
    assertThat(statuses).containsOnly("ISSUED");

    // The VAT report: what the ledger owes TRA, and the sales split by category.
    eventually(() -> vat().read("$.data.outputVat", Double.class) == 180.0);
    Response vat = vat();
    assertThat(category(vat, "STANDARD", "salesNet")).isEqualTo(1000.0);
    assertThat(category(vat, "STANDARD", "salesTax")).isEqualTo(180.0);
    assertThat(category(vat, "EXEMPT", "salesNet")).isEqualTo(1500.0);
    assertThat(category(vat, "EXEMPT", "salesTax")).isEqualTo(0.0);
    assertThat(category(vat, "ZERO_RATED", "salesNet")).isEqualTo(300.0);
    assertThat(vat.read("$.data.netPayable", Double.class)).isEqualTo(180.0);
    assertBooksRight();
  }

  @Test
  @DisplayName("a return gets a credit note against the sale's receipt")
  void creditNote() {
    Response sale = sell(lines(lotion, 2), 2360);
    String uid = sale.string("$.data.uid");
    String receipt = issued(uid).string("$.data.fiscalReference");
    String line = sale.string("$.data.lines[0].uid");

    Response returned =
        till.post(
                "/api/v1/sales/" + uid + "/returns",
                """
                {"reason":"Wrong size","lines":[{"saleLineUid":"%s","quantity":1}]}
                """
                    .formatted(line))
            .expect(201);
    assertThat(returned.string("$.data.fiscalStatus")).isEqualTo("PENDING");

    eventually(
        () ->
            "ISSUED"
                .equals(
                    till.get("/api/v1/sales/" + uid)
                        .expect(200)
                        .json()
                        .read("$.data.fiscalDocuments[1].status", String.class)));
    Response after = till.get("/api/v1/sales/" + uid).expect(200);
    assertThat(after.string("$.data.fiscalDocuments[1].kind")).isEqualTo("CREDIT_NOTE");
    String note = after.string("$.data.fiscalDocuments[1].receiptNumber");
    assertThat(note).startsWith("SIM-HQ-C").isNotEqualTo(receipt);
    assertThat(after.read("$.data.fiscalDocuments[1].total", Double.class)).isEqualTo(1180.0);
    assertThat(after.read("$.data.fiscalDocuments[1].taxTotal", Double.class)).isEqualTo(180.0);
    assertThat(after.string("$.data.fiscalDocuments[1].qrPayload"))
        .contains("SIMULATED FISCAL CREDIT_NOTE");
    assertThat(
            till.get("/api/v1/sale-returns")
                .expect(200)
                .json()
                .read("$.data.items[0].fiscalReference", String.class))
        .isEqualTo(note);

    // The VAT given back on the return comes off what is owed.
    eventually(() -> vat().read("$.data.vatReversed", Double.class) == 180.0);
    Response vat = vat();
    assertThat(vat.read("$.data.netPayable", Double.class)).isEqualTo(180.0);
    assertThat(category(vat, "STANDARD", "returnsTax")).isEqualTo(180.0);
    assertThat(category(vat, "STANDARD", "tax")).isEqualTo(180.0);
    assertBooksRight();
  }

  @Test
  @DisplayName("a branch's day closes with a Z report, by hand and by the recorded job")
  void zReport() throws Exception {
    Response first = sell(lines(lotion, 1), 1180);
    Response second = sell(lines(paracetamol, 2), 1000);
    issued(first.string("$.data.uid"));
    issued(second.string("$.data.uid"));
    String line = first.string("$.data.lines[0].uid");
    till.post(
            "/api/v1/sales/" + first.string("$.data.uid") + "/returns",
            "{\"reason\":\"Allergy\",\"lines\":[{\"saleLineUid\":\"%s\",\"quantity\":1}]}"
                .formatted(line))
        .expect(201);
    eventually(
        () ->
            "ISSUED"
                .equals(
                    till.get("/api/v1/sale-returns")
                        .expect(200)
                        .json()
                        .read("$.data.items[0].fiscalStatus", String.class)));

    // Yesterday's documents: the job closes that day itself.
    owner(
        "update sales.fiscal_documents set business_date = business_date - 1"
            + " where kind <> 'Z_REPORT'");
    invoke(context.getBean("fiscalSchedule"), "run");
    LocalDate yesterday = LocalDate.now(DAR).minusDays(1);
    Response reports = admin.get("/api/v1/fiscal/z-reports?branchUid=" + branchUid).expect(200);
    assertThat(reports.json().read("$.data.items[*].businessDate", List.class))
        .containsExactly(yesterday.toString());
    assertThat(reports.string("$.data.items[0].status")).isEqualTo("ISSUED");
    assertThat(reports.string("$.data.items[0].kind")).isEqualTo("Z_REPORT");
    assertThat(reports.string("$.data.items[0].receiptNumber"))
        .isEqualTo("SIM-HQ-Z" + yesterday.toString().replace("-", ""));
    assertThat(reports.read("$.data.items[0].z.receipts", Integer.class)).isEqualTo(2);
    assertThat(reports.read("$.data.items[0].z.receiptsTotal", Double.class)).isEqualTo(2180.0);
    assertThat(reports.read("$.data.items[0].z.receiptsTax", Double.class)).isEqualTo(180.0);
    assertThat(reports.read("$.data.items[0].z.creditNotes", Integer.class)).isEqualTo(1);
    assertThat(reports.read("$.data.items[0].z.creditNotesTotal", Double.class)).isEqualTo(1180.0);
    assertThat(reports.read("$.data.items[0].total", Double.class)).isEqualTo(1000.0);
    assertThat(reports.read("$.data.items[0].z.exemptNet", Double.class)).isEqualTo(1000.0);
    assertThat(reports.read("$.data.items[0].z.standardNet", Double.class)).isEqualTo(0.0);
    JobRuns.JobStatus job =
        jobRuns.statuses().stream()
            .filter(status -> status.job().equals("fiscal-documents"))
            .findFirst()
            .orElseThrow();
    assertThat(job.lastRun().outcome()).isEqualTo(JobRuns.Outcome.OK);
    assertThat(job.lastRun().message()).contains("branch days closed");

    // Today by hand: a sale since closes into today's report; asked again, the same report.
    sell(lines(export, 1), 300);
    eventually(() -> statuses().stream().allMatch("ISSUED"::equals));
    Response today =
        admin
            .post("/api/v1/fiscal/z-reports", "{\"branchUid\":\"%s\"}".formatted(branchUid))
            .expect(200);
    assertThat(today.string("$.data.status")).isEqualTo("ISSUED");
    assertThat(today.string("$.data.businessDate")).isEqualTo(LocalDate.now(DAR).toString());
    assertThat(today.read("$.data.z.receipts", Integer.class)).isEqualTo(1);
    assertThat(today.read("$.data.z.zeroRatedNet", Double.class)).isEqualTo(300.0);
    assertThat(
            admin
                .post("/api/v1/fiscal/z-reports", "{\"branchUid\":\"%s\"}".formatted(branchUid))
                .expect(200)
                .string("$.data.uid"))
        .isEqualTo(today.string("$.data.uid"));
    assertThat(
            admin
                .post(
                    "/api/v1/fiscal/z-reports",
                    "{\"branchUid\":\"%s\",\"day\":\"%s\"}"
                        .formatted(branchUid, LocalDate.now(DAR).plusDays(1)))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_day");
  }

  @Test
  @DisplayName("a receipt the authority refuses is FAILED, and is sent again once corrected")
  void refusedReceipt() {
    admin.put("/api/v1/settings", "{\"tin\":\"000000000\"}").expect(200);
    String uid = sell(lines(paracetamol, 1), 500).string("$.data.uid");
    eventually(
        () ->
            "FAILED"
                .equals(
                    till.get("/api/v1/sales/" + uid)
                        .expect(200)
                        .json()
                        .read("$.data.fiscalStatus", String.class)));
    Response failed = till.get("/api/v1/sales/" + uid).expect(200);
    assertThat(failed.read("$.data.fiscalDue", Boolean.class)).isTrue();
    assertThat(failed.string("$.data.fiscalDocuments[0].status")).isEqualTo("FAILED");
    assertThat(failed.string("$.data.fiscalDocuments[0].lastError")).contains("SIMULATED");
    String document = failed.string("$.data.fiscalDocuments[0].uid");

    admin.put("/api/v1/settings", "{\"tin\":\"123456789\"}").expect(200);
    Response retried = till.post("/api/v1/fiscal/documents/" + document + "/retry", "{}");
    assertThat(retried.expect(200).string("$.data.status")).isEqualTo("ISSUED");
    assertThat(retried.string("$.data.sellerTin")).isEqualTo("123456789");
    assertThat(retried.read("$.data.attempts", Integer.class)).isEqualTo(2);
    Response sale = till.get("/api/v1/sales/" + uid).expect(200);
    assertThat(sale.string("$.data.fiscalStatus")).isEqualTo("ISSUED");
    assertThat(sale.read("$.data.fiscalDue", Boolean.class)).isFalse();
    assertThat(
            till.post("/api/v1/fiscal/documents/" + document + "/retry", "{}")
                .expect(409)
                .string("$.reason"))
        .isEqualTo("fiscal_document_issued");
  }

  @Test
  @DisplayName("input VAT on a goods receipt and an expense goes to 1150; the books balance")
  void inputVat() {
    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"VAT Supplier\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");
    double inventoryBefore = ledger("1200");
    double stockBefore = stockAtCost();
    Response receipt =
        till.post(
                "/api/v1/goods-receipts",
                """
                {"supplierUid":"%s","supplierInvoiceNumber":"VAT-1","vatAmount":1800,"lines":[
                  {"productUid":"%s","batchNumber":"G-1","expiryDate":"%s","quantity":10,
                   "unitCost":1000}]}
                """
                    .formatted(supplier, lotion, LocalDate.now().plusYears(1)))
            .expect(201);
    // Owed: the goods and their VAT; the stock is valued without it.
    assertThat(receipt.read("$.data.total", Double.class)).isEqualTo(11800.0);
    assertThat(receipt.read("$.data.vatTotal", Double.class)).isEqualTo(1800.0);
    assertThat(receipt.read("$.data.baseVatTotal", Double.class)).isEqualTo(1800.0);
    eventually(() -> ledger("1150") == 1800.0);
    assertThat(ledger("1200")).isEqualTo(inventoryBefore + 10000.0);
    assertThat(stockAtCost()).isEqualTo(stockBefore + 10000.0);
    assertThat(ledger("2000")).isEqualTo(11800.0);

    Response expense =
        till.post(
                "/api/v1/expenses",
                """
                {"category":"UTILITIES","description":"Electricity","amount":11800,
                 "vatAmount":1800,"paymentMethod":"CASH"}
                """)
            .expect(201);
    assertThat(expense.read("$.data.vatAmount", Double.class)).isEqualTo(1800.0);
    assertThat(ledger("6010")).isEqualTo(10000.0);
    assertThat(ledger("1150")).isEqualTo(3600.0);
    assertThat(
            till.post(
                    "/api/v1/expenses",
                    """
                    {"category":"RENT","description":"Rent","amount":100,"vatAmount":200,
                     "paymentMethod":"CASH"}
                    """)
                .expect(422)
                .string("$.reason"))
        .isEqualTo("vat_exceeds_amount");

    sell(lines(lotion, 1), 1180);
    eventually(() -> vat().read("$.data.outputVat", Double.class) == 180.0);
    Response vat = vat();
    assertThat(vat.read("$.data.inputVat", Double.class)).isEqualTo(3600.0);
    // Output 180 less input 3,600: a credit of 3,420 to carry forward or claim back.
    assertThat(vat.read("$.data.netPayable", Double.class)).isEqualTo(-3420.0);
    assertBooksRight();
  }

  @Test
  @DisplayName("a pharmacy that is not VAT-registered charges no VAT and books none")
  void notRegistered() {
    admin.put("/api/v1/settings", "{\"vatRegistered\":false}").expect(200);
    Response sale = sell(lines(lotion, 1, paracetamol, 1), 1680);
    assertThat(sale.read("$.data.total", Double.class)).isEqualTo(1680.0);
    assertThat(sale.read("$.data.taxTotal", Double.class)).isEqualTo(0.0);
    assertThat(sale.read("$.data.lines[0].taxRatePercent", Double.class)).isEqualTo(0.0);
    assertThat(
            till.get("/api/v1/pos/products?q=Lotion")
                .expect(200)
                .read("$.data.items[0].taxRatePercent", Double.class))
        .isEqualTo(0.0);
    // The product keeps its category and the rate it is sold at once registered.
    Response product = admin.get("/api/v1/products/" + lotion).expect(200);
    assertThat(product.string("$.data.taxCategory")).isEqualTo("STANDARD");
    assertThat(product.read("$.data.taxRatePercent", Double.class)).isEqualTo(18.0);

    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Plain Supplier\",\"paymentTerms\":\"COD\"}")
            .expect(201)
            .string("$.data.uid");
    assertThat(
            till.post(
                    "/api/v1/goods-receipts",
                    """
                    {"supplierUid":"%s","vatAmount":180,"lines":[{"productUid":"%s",
                     "batchNumber":"N-1","expiryDate":"%s","quantity":1,"unitCost":1000}]}
                    """
                        .formatted(supplier, lotion, LocalDate.now().plusYears(1)))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("vat_not_registered");
    assertThat(
            till.post(
                    "/api/v1/expenses",
                    """
                    {"category":"RENT","description":"Rent","amount":1180,"vatAmount":180,
                     "paymentMethod":"CASH"}
                    """)
                .expect(422)
                .string("$.reason"))
        .isEqualTo("vat_not_registered");

    eventually(() -> ledger("4000") == 1680.0);
    assertThat(ledger("2100")).isEqualTo(0.0);
    assertThat(vat().read("$.data.netPayable", Double.class)).isEqualTo(0.0);
    assertBooksRight();
  }

  @Test
  @DisplayName("a product's category decides its rate; the standard rate follows the settings")
  void categoriesAndTheStandardRate() {
    Response exempt =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Amoxicillin 250mg","genericName":"Amoxicillin","dosageForm":"CAPSULE",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                 "sellingPrice":1000,"costPrice":400,"taxRatePercent":18,"taxCategory":"EXEMPT"}
                """)
            .expect(201);
    assertThat(exempt.read("$.data.taxRatePercent", Double.class)).isEqualTo(0.0);
    // An older client sends only a rate: above 0 is STANDARD, at the pharmacy's standard rate.
    Response rated =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Hand Cream","dosageForm":"LOTION","schedule":"OTC","productType":"COSMETIC",
                 "trackingMode":"BATCH","sellingPrice":2000,"costPrice":900,"taxRatePercent":18}
                """)
            .expect(201);
    assertThat(rated.string("$.data.taxCategory")).isEqualTo("STANDARD");

    admin.put("/api/v1/settings", "{\"defaultTaxRatePercent\":16}").expect(200);
    eventually(
        () ->
            admin
                    .get("/api/v1/products/" + lotion)
                    .expect(200)
                    .read("$.data.taxRatePercent", Double.class)
                == 16.0);
    assertThat(
            admin
                .get("/api/v1/products/" + paracetamol)
                .expect(200)
                .read("$.data.taxRatePercent", Double.class))
        .isEqualTo(0.0);
    // 1,180 with 16% inside: VAT 1,180 x 16 / 116 = 162.76.
    Response sale =
        till.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
                 "payments":[{"method":"CASH","amount":1180}]}
                """
                    .formatted(UUID.randomUUID(), lotion))
            .expect(201);
    assertThat(sale.read("$.data.taxTotal", Double.class)).isEqualTo(162.76);
  }

  // ------------------------------------------------------------------ helpers

  private String product(String name, String type, int price, String category) {
    boolean medicine = "MEDICINE".equals(type);
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s",%s"schedule":"OTC","productType":"%s","trackingMode":"BATCH",
             "sellingPrice":%d,"costPrice":200,"taxCategory":"%s"}
            """
                .formatted(
                    name,
                    medicine
                        ? "\"genericName\":\""
                            + name.split(" ")[0]
                            + "\",\"dosageForm\":\"TABLET\","
                        : "\"dosageForm\":\"LOTION\",",
                    type,
                    price,
                    category))
        .expect(201)
        .string("$.data.uid");
  }

  private static String lines(Object... productsAndQuantities) {
    StringBuilder lines = new StringBuilder();
    for (int i = 0; i < productsAndQuantities.length; i += 2) {
      if (i > 0) {
        lines.append(',');
      }
      lines
          .append("{\"productUid\":\"")
          .append(productsAndQuantities[i])
          .append("\",\"quantity\":")
          .append(productsAndQuantities[i + 1])
          .append('}');
    }
    return lines.toString();
  }

  private Response sell(String lines, double paid) {
    return till.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[%s],"payments":[{"method":"CASH","amount":%s}]}
            """
                .formatted(UUID.randomUUID(), lines, paid))
        .expect(201);
  }

  /** The sale once its receipt is issued. */
  private Response issued(String saleUid) {
    eventually(
        () ->
            "ISSUED"
                .equals(
                    till.get("/api/v1/sales/" + saleUid)
                        .expect(200)
                        .json()
                        .read("$.data.fiscalStatus", String.class)));
    return till.get("/api/v1/sales/" + saleUid).expect(200);
  }

  private List<String> statuses() {
    return till.get("/api/v1/sales").expect(200).json().read("$.data.items[*].fiscalStatus");
  }

  private Response vat() {
    return admin.get("/api/v1/reports/vat").expect(200);
  }

  private static double category(Response vat, String category, String figure) {
    List<Object> values =
        vat.json().read("$.data.categories[?(@.category == '" + category + "')]." + figure);
    return Double.parseDouble(values.getFirst().toString());
  }

  private double ledger(String code) {
    List<Object> balance =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + code + "')].balance");
    return balance.isEmpty() ? 0.0 : Double.parseDouble(balance.getFirst().toString());
  }

  private double stockAtCost() {
    return till.get("/api/v1/reports/stock-valuation")
        .expect(200)
        .read("$.data[0].costValue", Double.class);
  }

  /**
   * The books are right: every reaction posted (no document is left out), the daily balances agree
   * with the journal, the trial balance and the balance sheet balance, and the stock at cost moved
   * exactly as the ledger's inventory did.
   */
  private void assertBooksRight() {
    eventually(
        () -> {
          List<Object> mismatches =
              admin
                  .get("/api/v1/finance/reconciliation/balances?days=3")
                  .expect(200)
                  .json()
                  .read("$.data");
          return mismatches.isEmpty()
              && Math.abs(stockAtCost() - ledger("1200") - openingGap) < 0.005;
        });
    List<Object> unposted =
        admin.get("/api/v1/finance/reconciliation").expect(200).json().read("$.data");
    assertThat(unposted).isEmpty();
    Response trial = admin.get("/api/v1/reports/trial-balance").expect(200);
    assertThat(trial.read("$.data.totalDebit", Double.class))
        .isEqualTo(trial.read("$.data.totalCredit", Double.class));
    assertThat(
            admin
                .get("/api/v1/reports/balance-sheet")
                .expect(200)
                .read("$.data.balanced", Boolean.class))
        .isTrue();
  }

  /** A statement as the schema owner, in this pharmacy (row-level security is forced on it too). */
  private void owner(String statement) throws Exception {
    try (Connection c = DriverManager.getConnection(url, owner, ownerPassword)) {
      c.setAutoCommit(false);
      try (PreparedStatement scope =
          c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        scope.setString(1, tenantUid);
        try (ResultSet ignored = scope.executeQuery()) {
          // scoped
        }
      }
      try (PreparedStatement s = c.prepareStatement(statement)) {
        s.execute();
      }
      c.commit();
    }
  }

  private static void invoke(Object bean, String name) throws Exception {
    Object target = AopTestUtils.getUltimateTargetObject(bean);
    Method method = target.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(target);
  }
}
