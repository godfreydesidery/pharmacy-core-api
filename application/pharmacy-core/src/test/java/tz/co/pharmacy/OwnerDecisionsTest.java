package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.pharmacy.support.Api.eventually;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpiryProvisions;
import tz.co.pharmacy.modules.finance.ports.inbound.MonthEndRuns;
import tz.co.pharmacy.modules.finance.ports.inbound.VatApportionments;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * The owner's decisions of 24 Nov 2026 (Batch 10, stream 5): the pharmacy settings for shared
 * counter PCs, the expiry write-down and the VAT apportionment; the monthly expiry write-down
 * (STD-3) re-measured each month against 1210/5110; the journal line check; the partial-exemption
 * VAT apportionment with its year-end true-up (STD-2); and debit notes on sales (fiscal DEBIT_NOTE
 * through the simulator) and from suppliers. The books balance after every new posting.
 */
@TestPropertySource(properties = "pharmacy.integrations.fiscal.provider=simulator")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnerDecisionsTest extends IntegrationTest {

  private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");

  @Autowired MonthEndRuns months;
  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api admin;
  private Api till;
  private String tenantUid;
  private String branchUid;
  private String email;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "OD" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    email = code.toLowerCase() + "@test.local";
    tenantUid =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Decisions %s","legalName":"Decisions %s Ltd","country":"TZ",
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
  }

  // ------------------------------------------------------------------ settings

  @Test
  @DisplayName("settings: shared PCs off, write-down on with its bands, automatic VAT; validated")
  void settings() {
    Response settings = admin.get("/api/v1/settings").expect(200);
    assertThat(settings.read("$.data.sharedCounterPcs", Boolean.class)).isFalse();
    assertThat(settings.read("$.data.expiryProvisionEnabled", Boolean.class)).isTrue();
    assertThat(settings.json().read("$.data.expiryProvisionBands[*].daysToExpiry", List.class))
        .containsExactly(0, 30, 90);
    assertThat(settings.json().read("$.data.expiryProvisionBands[*].percent", List.class))
        .containsExactly(100.0, 50.0, 25.0);
    assertThat(settings.string("$.data.vatApportionmentMethod")).isEqualTo("AUTOMATIC");
    assertThat(settings.read("$.data.vatYearEndMonth", Integer.class)).isEqualTo(12);
    assertThat(settings.read("$.data.vatRatioRoundUp", Boolean.class)).isTrue();

    String version = settings.header("ETag");
    Response changed =
        admin.put(
            "/api/v1/settings",
            """
            {"sharedCounterPcs":true,"expiryProvisionEnabled":true,
             "expiryProvisionBands":[{"daysToExpiry":0,"percent":100},
               {"daysToExpiry":60,"percent":40}],
             "vatApportionmentMethod":"fixed","vatFixedRecoverablePercent":62.5,
             "vatYearEndMonth":6,"vatRatioRoundUp":false}
            """,
            "If-Match",
            version);
    changed.expect(200);
    assertThat(changed.read("$.data.sharedCounterPcs", Boolean.class)).isTrue();
    assertThat(changed.json().read("$.data.expiryProvisionBands[*].daysToExpiry", List.class))
        .containsExactly(0, 60);
    assertThat(changed.string("$.data.vatApportionmentMethod")).isEqualTo("FIXED");
    assertThat(changed.read("$.data.vatFixedRecoverablePercent", Double.class)).isEqualTo(62.5);
    assertThat(changed.read("$.data.vatYearEndMonth", Integer.class)).isEqualTo(6);

    // Audited, old and new.
    Response audit = admin.get("/api/v1/audit?action=SETTINGS_CHANGED").expect(200);
    assertThat(audit.string("$.data.items[0].before.sharedCounterPcs")).isEqualTo("false");
    assertThat(audit.string("$.data.items[0].after.sharedCounterPcs")).isEqualTo("true");
    assertThat(audit.string("$.data.items[0].after.vatApportionmentMethod")).isEqualTo("FIXED");

    for (String bad :
        List.of(
            "{\"expiryProvisionBands\":[]}",
            "{\"expiryProvisionBands\":[{\"daysToExpiry\":30,\"percent\":50},"
                + "{\"daysToExpiry\":0,\"percent\":100}]}",
            "{\"expiryProvisionBands\":[{\"daysToExpiry\":0,\"percent\":50},"
                + "{\"daysToExpiry\":30,\"percent\":60}]}",
            "{\"expiryProvisionBands\":[{\"daysToExpiry\":0,\"percent\":101}]}",
            "{\"expiryProvisionBands\":[{\"daysToExpiry\":800,\"percent\":10}]}",
            "{\"expiryProvisionBands\":[{\"daysToExpiry\":0,\"percent\":90},"
                + "{\"daysToExpiry\":10,\"percent\":80},{\"daysToExpiry\":20,\"percent\":70},"
                + "{\"daysToExpiry\":30,\"percent\":60},{\"daysToExpiry\":40,\"percent\":50},"
                + "{\"daysToExpiry\":50,\"percent\":40}]}")) {
      Response refused = admin.put("/api/v1/settings", bad).expect(422);
      assertThat(refused.string("$.reason")).as(bad).isEqualTo("invalid_provision_bands");
      assertThat(refused.read("$.maxBands", Integer.class)).isEqualTo(5);
    }
    assertThat(
            admin
                .put("/api/v1/settings", "{\"vatApportionmentMethod\":\"GUESS\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_apportionment_method");
    assertThat(
            admin
                .put("/api/v1/settings", "{\"vatFixedRecoverablePercent\":120}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_recoverable_percent");
    assertThat(
            admin
                .put("/api/v1/settings", "{\"vatYearEndMonth\":13}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_year_end_month");
  }

  @Test
  @DisplayName("the fixed VAT method needs its percentage")
  void fixedNeedsPercent() {
    assertThat(
            admin
                .put("/api/v1/settings", "{\"vatApportionmentMethod\":\"FIXED\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("recoverable_percent_required");
  }

  // ------------------------------------------------------------------ expiry write-down

  @Test
  @DisplayName(
      "STD-3: the write-down follows the bands, is re-measured monthly and can be released")
  void expiryWriteDown() throws Exception {
    String product = product("Amoxicillin 250mg", "MEDICINE", 500, "EXEMPT");
    // Measured at 31 Mar 2027: expired, 20 days left, 76 days left, far away.
    opening(product, "A", "2027-03-15", 10, 100);
    opening(product, "B", "2027-04-20", 10, 100);
    opening(product, "C", "2027-06-15", 40, 100);
    opening(product, "D", "2028-12-01", 10, 100);

    ExpiryProvisions.ExpiryProvisionRun march =
        inTenant(() -> months.expiryProvision(YearMonth.of(2027, 3)));
    assertThat(march.stockAtCost()).isEqualByComparingTo("7000");
    // 1000 × 100% + 1000 × 50% + 4000 × 25%.
    assertThat(march.provision()).isEqualByComparingTo("2500");
    assertThat(march.adjustment()).isEqualByComparingTo("2500");
    assertThat(march.journalNumber()).startsWith("JV-2027-");
    assertThat(march.branches()).hasSize(1);
    assertThat(march.branches().getFirst().bands())
        .extracting(ExpiryProvisions.ProvisionBandMeasure::costAtRisk)
        .usingElementComparator(java.math.BigDecimal::compareTo)
        .containsExactly(
            new java.math.BigDecimal("1000"),
            new java.math.BigDecimal("1000"),
            new java.math.BigDecimal("4000"));
    assertThat(ledger("1210")).isEqualTo(-2500.0);
    assertThat(ledger("5110")).isEqualTo(2500.0);
    // A second run of the month changes nothing.
    assertThat(inTenant(() -> months.expiryProvision(YearMonth.of(2027, 3))).uid())
        .isEqualTo(march.uid());
    assertThat(ledger("1210")).isEqualTo(-2500.0);

    // The rule changes from the next run on; March keeps the rule it used.
    admin
        .put(
            "/api/v1/settings",
            "{\"expiryProvisionBands\":[{\"daysToExpiry\":0,\"percent\":100},"
                + "{\"daysToExpiry\":60,\"percent\":40}]}")
        .expect(200);
    ExpiryProvisions.ExpiryProvisionRun april =
        inTenant(() -> months.expiryProvision(YearMonth.of(2027, 4)));
    // At 30 Apr: A and B expired (2000 × 100%), C has 46 days (4000 × 40%), D none.
    assertThat(april.provision()).isEqualByComparingTo("3600");
    assertThat(april.previousProvision()).isEqualByComparingTo("2500");
    assertThat(april.adjustment()).isEqualByComparingTo("1100");
    assertThat(april.bands())
        .extracting(ExpiryProvisions.ProvisionRuleBand::daysToExpiry)
        .containsExactly(0, 60);
    assertThat(ledger("1210")).isEqualTo(-3600.0);
    Response report =
        admin.get("/api/v1/reports/expiry-provision?from=2027-01-01&to=2027-12-31").expect(200);
    assertThat(report.json().read("$.data[*].period", List.class))
        .containsExactly("2027-04", "2027-03");
    assertThat(report.json().read("$.data[1].bands[*].daysToExpiry", List.class))
        .containsExactly(0, 30, 90);

    // Turned off: the provision is released.
    admin.put("/api/v1/settings", "{\"expiryProvisionEnabled\":false}").expect(200);
    ExpiryProvisions.ExpiryProvisionRun may =
        inTenant(() -> months.expiryProvision(YearMonth.of(2027, 5)));
    assertThat(may.provision()).isEqualByComparingTo("0");
    assertThat(may.adjustment()).isEqualByComparingTo("-3600");
    assertThat(ledger("1210")).isEqualTo(0.0);
    assertThat(ledger("5110")).isEqualTo(0.0);
    assertBooksBalance();
  }

  @Test
  @DisplayName("STD-3: a journal line can never be both a debit and a credit (a database check)")
  void journalLineCheck() throws Exception {
    String product = product("Cetirizine 10mg", "MEDICINE", 300, "EXEMPT");
    opening(product, "E", "2027-03-01", 1, 100);
    inTenant(() -> months.expiryProvision(YearMonth.of(2027, 3)));
    try (Connection c = dataSource.getConnection()) {
      try (ResultSet valid =
          c.createStatement()
              .executeQuery(
                  "select convalidated from pg_constraint"
                      + " where conname = 'journal_lines_debit_or_credit'")) {
        assertThat(valid.next()).isTrue();
        assertThat(valid.getBoolean(1)).isTrue();
      }
      c.setAutoCommit(false);
      try (PreparedStatement scope =
          c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        scope.setString(1, tenantUid);
        scope.executeQuery().close();
      }
      try (PreparedStatement both =
          c.prepareStatement(
              "insert into finance.journal_lines (uid, tenant_uid, entry_id, account_code,"
                  + " account_name, debit, credit, created_at, created_by)"
                  + " select gen_random_uuid(), tenant_uid, id, '1000', 'Cash', 5, 5, now(), 'test'"
                  + " from finance.journal_entries limit 1")) {
        assertThatThrownBy(both::executeUpdate)
            .hasMessageContaining("journal_lines_debit_or_credit");
      } finally {
        c.rollback();
      }
    }
  }

  // ------------------------------------------------------------------ VAT apportionment

  @Test
  @DisplayName("STD-2: input VAT split by attribution and the taxable share, trued up at year end")
  void vatApportionment() {
    String lotion = product("Body Lotion 200ml", "COSMETIC", 1180, "STANDARD");
    String paracetamol = product("Paracetamol 500mg", "MEDICINE", 500, "EXEMPT");
    opening(lotion, "L", LocalDate.now(DAR).plusYears(2).toString(), 20, 200);
    opening(paracetamol, "P", LocalDate.now(DAR).plusYears(2).toString(), 20, 200);
    // Taxable sales 1,000 net (VAT inside the price), exempt 3,000.
    sell(lines(lotion, 1, paracetamol, 6), 4180, null);
    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Mixed Supplier\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");
    // Exempt goods only: 180 not reclaimable. Taxable only: 90 reclaimable. Mixed by value: 50/50.
    receive(supplier, "[" + line(paracetamol, 1, 1000) + "]", 180);
    String taxable = receive(supplier, "[" + line(lotion, 1, 500) + "]", 90);
    receive(supplier, "[" + line(lotion, 1, 1000) + "," + line(paracetamol, 1, 1000) + "]", 100);
    // An overhead: residual.
    till.post(
            "/api/v1/expenses",
            "{\"category\":\"UTILITIES\",\"description\":\"Power\",\"amount\":1180,"
                + "\"vatAmount\":40,\"paymentMethod\":\"CASH\"}")
        .expect(201);
    eventually(() -> ledger("1150") == 410.0);
    YearMonth month = YearMonth.from(LocalDate.now(DAR));

    // A fixed 50% first: 180 + 50 + 40 × 50% = 250 not reclaimable.
    admin
        .put(
            "/api/v1/settings",
            "{\"vatApportionmentMethod\":\"FIXED\",\"vatFixedRecoverablePercent\":50}")
        .expect(200);
    List<VatApportionments.VatApportionmentRun> fixed =
        inTenant(() -> months.vatApportionment(month));
    assertThat(fixed).hasSize(1);
    VatApportionments.VatApportionmentRun monthly = fixed.getFirst();
    assertThat(monthly.kind()).isEqualTo("MONTHLY");
    assertThat(monthly.directExemptVat()).isEqualByComparingTo("230");
    assertThat(monthly.directTaxableVat()).isEqualByComparingTo("140");
    assertThat(monthly.residualVat()).isEqualByComparingTo("40");
    assertThat(monthly.nonReclaimable()).isEqualByComparingTo("250");
    assertThat(ledger("5150")).isEqualTo(250.0);
    assertThat(ledger("1150")).isEqualTo(160.0);

    // Automatic, the year ending this month: the true-up uses the taxable share, 25%.
    admin
        .put(
            "/api/v1/settings",
            "{\"vatApportionmentMethod\":\"AUTOMATIC\",\"vatYearEndMonth\":"
                + month.getMonthValue()
                + "}")
        .expect(200);
    List<VatApportionments.VatApportionmentRun> runs =
        inTenant(() -> months.vatApportionment(month));
    assertThat(runs)
        .extracting(VatApportionments.VatApportionmentRun::kind)
        .containsExactly("MONTHLY", "ANNUAL");
    VatApportionments.VatApportionmentRun annual = runs.get(1);
    assertThat(annual.ratioPercent()).isEqualByComparingTo("25");
    assertThat(annual.taxableSales()).isEqualByComparingTo("1000");
    assertThat(annual.exemptSales()).isEqualByComparingTo("3000");
    // 180 + 50 + 40 × 75% = 260, of which 250 already moved.
    assertThat(annual.nonReclaimable()).isEqualByComparingTo("260");
    assertThat(annual.adjustment()).isEqualByComparingTo("10");
    assertThat(ledger("5150")).isEqualTo(260.0);

    // The month's report, to its last day (the runs are booked on it).
    String range = "?from=" + month.atDay(1) + "&to=" + month.atEndOfMonth();
    Response vat = admin.get("/api/v1/reports/vat" + range).expect(200);
    assertThat(vat.read("$.data.inputVatBeforeApportionment", Double.class)).isEqualTo(410.0);
    assertThat(vat.read("$.data.nonReclaimableInputVat", Double.class)).isEqualTo(260.0);
    assertThat(vat.read("$.data.reclaimableInputVat", Double.class)).isEqualTo(150.0);
    assertThat(vat.json().read("$.data.apportionments[*].kind", List.class))
        .contains("MONTHLY", "ANNUAL");

    // A supplier's debit note on the taxable receipt: 100 + 18 VAT more owed.
    Response note =
        admin
            .post(
                "/api/v1/goods-receipts/" + taxable + "/debit-notes",
                "{\"netAmount\":100,\"vatAmount\":18,\"reason\":\"Price corrected\","
                    + "\"supplierReference\":\"SUP-DN-7\"}")
            .expect(201);
    assertThat(note.string("$.data.number")).isEqualTo("PDN-HQ-000001");
    assertThat(note.read("$.data.total", Double.class)).isEqualTo(118.0);
    eventually(() -> ledger("5120") == 100.0);
    assertThat(ledger("1150")).isEqualTo(168.0);
    Response statement = admin.get("/api/v1/suppliers/" + supplier + "/statement").expect(200);
    assertThat(statement.json().read("$.data.lines[?(@.type == 'DEBIT_NOTE')].amount", List.class))
        .containsExactly(118.0);
    assertThat(
            admin
                .get("/api/v1/reports/vat" + range)
                .expect(200)
                .read("$.data.debitNotes.purchasesVat", Double.class))
        .isEqualTo(18.0);

    // A payment naming the receipt may settle what the debit note raised, and no more.
    double receiptTotal =
        till.get("/api/v1/goods-receipts/" + taxable)
            .expect(200)
            .read("$.data.total", Double.class);
    String pay =
        "{\"supplierUid\":\"%s\",\"amount\":%s,\"paymentMethod\":\"CASH\",\"goodsReceiptUids\":[\"%s\"]}";
    Response over =
        admin.post(
            "/api/v1/finance/supplier-payments",
            pay.formatted(supplier, receiptTotal + 118 + 1, taxable));
    assertThat(over.expect(422).string("$.reason")).isEqualTo("overpayment");
    admin
        .post(
            "/api/v1/finance/supplier-payments",
            pay.formatted(supplier, receiptTotal + 118, taxable))
        .expect(201);
    assertBooksBalance();
  }

  // ------------------------------------------------------------------ sales debit notes

  @Test
  @DisplayName(
      "STD-2: a sales debit note charges the customer's account, with VAT and a fiscal note")
  void salesDebitNote() {
    String lotion = product("Hand Cream 50ml", "COSMETIC", 1180, "STANDARD");
    opening(lotion, "H", LocalDate.now(DAR).plusYears(2).toString(), 10, 200);
    String customer =
        admin
            .post("/api/v1/customers", "{\"name\":\"Asha Juma\",\"phone\":\"+255700000222\"}")
            .expect(201)
            .string("$.data.uid");
    String sale = sell(lines(lotion, 1), 1180, customer).string("$.data.uid");
    String anonymousSale = sell(lines(lotion, 1), 1180, null).string("$.data.uid");
    eventually(() -> ledger("2100") == 360.0);

    assertThat(
            admin
                .post(
                    "/api/v1/sales/" + anonymousSale + "/debit-notes",
                    "{\"netAmount\":100,\"reason\":\"Under-billed\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("debit_note_needs_customer");
    // The note's fiscal document refers to the sale's receipt: let it be issued first.
    eventually(
        () ->
            "ISSUED"
                .equals(
                    till.get("/api/v1/sales/" + sale)
                        .expect(200)
                        .json()
                        .read("$.data.fiscalStatus", String.class)));
    Response note =
        admin
            .post(
                "/api/v1/sales/" + sale + "/debit-notes",
                "{\"netAmount\":200,\"reason\":\"Under-billed: price list was old\"}")
            .expect(201);
    assertThat(note.string("$.data.number")).isEqualTo("SDN-HQ-000001");
    assertThat(note.read("$.data.tax", Double.class)).isEqualTo(36.0);
    assertThat(note.read("$.data.total", Double.class)).isEqualTo(236.0);
    eventually(() -> ledger("1100") == 236.0);
    assertThat(ledger("2100")).isEqualTo(396.0);

    Response statement =
        admin.get("/api/v1/receivables/accounts/" + customer + "/statement").expect(200);
    assertThat(statement.json().read("$.data.entries.items[*].reference", List.class))
        .contains("SDN-HQ-000001");
    Response vat = admin.get("/api/v1/reports/vat").expect(200);
    assertThat(vat.read("$.data.debitNotes.salesTax", Double.class)).isEqualTo(36.0);
    List<Object> standardNotes =
        vat.json().read("$.data.categories[?(@.category == 'STANDARD')].debitNotesNet");
    assertThat(Double.parseDouble(standardNotes.getFirst().toString())).isEqualTo(200.0);

    // Its fiscal document, through the provider port (the simulator here).
    eventually(
        () -> {
          List<String> kinds =
              till.get("/api/v1/sales/" + sale + "/fiscal-documents")
                  .expect(200)
                  .json()
                  .read("$.data[?(@.kind == 'DEBIT_NOTE' && @.status == 'ISSUED')].receiptNumber");
          return !kinds.isEmpty() && kinds.getFirst().startsWith("SIM-HQ-D");
        });
    assertThat(
            admin
                .get("/api/v1/sales/" + sale + "/debit-notes")
                .expect(200)
                .json()
                .read("$.data")
                .toString())
        .contains("SDN-HQ-000001");
    assertBooksBalance();
  }

  // ------------------------------------------------------------------ helpers

  private <T> T inTenant(Supplier<T> work) {
    UUID tenant = UUID.fromString(tenantUid);
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service("decisions-test", null, tenant),
        () -> TenantContext.callAs(tenant, work));
  }

  private String product(String name, String type, int price, String category) {
    boolean medicine = "MEDICINE".equals(type);
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s",%s"schedule":"OTC","productType":"%s","trackingMode":"BATCH_EXPIRY",
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

  private void opening(String product, String batch, String expiry, int quantity, int cost) {
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%d}
            """
                .formatted(product, batch, expiry, quantity, cost))
        .expect(201);
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

  private Response sell(String lines, double paid, String customer) {
    return till.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s",%s"lines":[%s],"payments":[{"method":"CASH","amount":%s}]}
            """
                .formatted(
                    UUID.randomUUID(),
                    customer == null ? "" : "\"customerUid\":\"" + customer + "\",",
                    lines,
                    paid))
        .expect(201);
  }

  private static String line(String product, int quantity, int unitCost) {
    return """
        {"productUid":"%s","batchNumber":"R-%s","expiryDate":"%s","quantity":%d,"unitCost":%d}
        """
        .formatted(
            product,
            UUID.randomUUID().toString().substring(0, 6),
            LocalDate.now(DAR).plusYears(1),
            quantity,
            unitCost);
  }

  private String receive(String supplier, String lines, int vat) {
    return till.post(
            "/api/v1/goods-receipts",
            "{\"supplierUid\":\"%s\",\"vatAmount\":%d,\"lines\":%s}"
                .formatted(supplier, vat, lines))
        .expect(201)
        .string("$.data.uid");
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

  /** Every new posting balanced: the daily balances agree, the trial balance and sheet balance. */
  private void assertBooksBalance() {
    eventually(
        () -> {
          List<Object> mismatches =
              admin
                  .get("/api/v1/finance/reconciliation/balances?days=3")
                  .expect(200)
                  .json()
                  .read("$.data");
          List<Object> unposted =
              admin.get("/api/v1/finance/reconciliation").expect(200).json().read("$.data");
          return mismatches.isEmpty() && unposted.isEmpty();
        });
    Response trial = admin.get("/api/v1/reports/trial-balance?asOf=2028-12-31").expect(200);
    assertThat(trial.read("$.data.totalDebit", Double.class))
        .isEqualTo(trial.read("$.data.totalCredit", Double.class));
    assertThat(
            admin
                .get("/api/v1/reports/balance-sheet?asOf=2028-12-31")
                .expect(200)
                .read("$.data.balanced", Boolean.class))
        .isTrue();
  }
}
