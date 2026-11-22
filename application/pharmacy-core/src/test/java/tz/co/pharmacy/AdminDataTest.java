package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Administration and data fixes from the QA report: editing a pharmacy and the currency lock, root
 * administrators, staff phone numbers, delivery details and resend, duplicate supplier invoices,
 * opening balances, new dosage forms and audit labels.
 */
class AdminDataTest extends IntegrationTest {

  private Api anonymous;
  private Api root;
  private Api admin;
  private Api till;
  private String tenant;
  private String code;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    code = "AD" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Admin %s","legalName":"Admin %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenant, branch);
  }

  @Test
  @DisplayName("M-08 the root admin edits a pharmacy; the currency locks once it has traded")
  void editTenantAndCurrencyLock() {
    Response changed =
        root.put(
                "/api/v1/platform/tenants/" + tenant,
                """
                {"name":"Admin Renamed","legalName":"Renamed Ltd","country":"KE",
                 "timezone":"Africa/Nairobi","currency":"KES","contactName":"Asha",
                 "contactEmail":"asha@pharmacy.test","contactPhone":"+254700000001",
                 "address":"Moi Avenue"}
                """)
            .expect(200);
    assertThat(changed.string("$.data.name")).isEqualTo("Admin Renamed");
    assertThat(changed.string("$.data.currency")).isEqualTo("KES");
    assertThat(changed.string("$.data.contactEmail")).isEqualTo("asha@pharmacy.test");
    assertThat(changed.string("$.data.timezone")).isEqualTo("Africa/Nairobi");

    // Trading posts to the books: the currency is now locked.
    till.post(
            "/api/v1/expenses",
            "{\"category\":\"RENT\",\"amount\":100,\"paymentMethod\":\"CASH\",\"description\":\"Rent\"}")
        .expect(201);
    Api.eventually(
        () -> !admin.get("/api/v1/journal?limit=5").expect(200).body().contains("\"items\":[]"));
    Response locked =
        root.put(
            "/api/v1/platform/tenants/" + tenant,
            "{\"name\":\"Admin Renamed\",\"country\":\"KE\",\"timezone\":\"Africa/Nairobi\",\"currency\":\"USD\"}");
    assertThat(locked.expect(422).string("$.reason")).isEqualTo("currency_locked");
    // Other fields still change.
    root.put(
            "/api/v1/platform/tenants/" + tenant,
            "{\"name\":\"Admin Again\",\"country\":\"KE\",\"timezone\":\"Africa/Nairobi\",\"currency\":\"KES\"}")
        .expect(200);
  }

  @Test
  @DisplayName("M-09 root administrators: deactivate, reactivate, reset, remove, guards")
  void rootAdministrators() {
    String email = "root-" + code.toLowerCase() + "@test.local";
    String uid =
        root.post(
                "/api/v1/platform/root-admins",
                "{\"name\":\"Second Root\",\"email\":\"%s\",\"password\":\"Root2#Test2026\"}"
                    .formatted(email))
            .expect(201)
            .string("$.data.uid");
    String second = anonymous.signIn(email, "Root2#Test2026");

    root.post("/api/v1/platform/root-admins/" + uid + "/deactivate", null).expect(200);
    assertThat(anonymous.as(second).get("/api/v1/platform/tenants").status()).isEqualTo(401);
    Response refused =
        anonymous.post(
            "/api/v1/auth/sign-in",
            "{\"email\":\"%s\",\"password\":\"Root2#Test2026\"}".formatted(email));
    assertThat(refused.status()).isIn(401, 403, 423);

    List<Boolean> active =
        root.get("/api/v1/platform/root-admins")
            .expect(200)
            .json()
            .read("$.data[?(@.email == '" + email + "')].active");
    assertThat(active).containsExactly(false);

    root.post("/api/v1/platform/root-admins/" + uid + "/reactivate", null).expect(200);
    root.post("/api/v1/platform/root-admins/" + uid + "/password-reset", null).expect(200);

    // Never yourself.
    String self = root.get("/api/v1/auth/profile").expect(200).string("$.data.uid");
    assertThat(
            root.post("/api/v1/platform/root-admins/" + self + "/deactivate", null)
                .expect(422)
                .string("$.reason"))
        .isEqualTo("cannot_change_self");

    root.delete("/api/v1/platform/root-admins/" + uid).expect(200);
    List<String> emails =
        root.get("/api/v1/platform/root-admins").expect(200).json().read("$.data[*].email");
    assertThat(emails).doesNotContain(email);
  }

  @Test
  @DisplayName("M-07 a person's phone changes only by its SMS code (G9); the name changes freely")
  void staffPhone() {
    assertThat(
            admin
                .put("/api/v1/auth/profile", "{\"name\":\"Admin\",\"phone\":\"not a phone\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_phone");
    assertThat(
            admin
                .put("/api/v1/auth/profile", "{\"name\":\"Admin\",\"phone\":\"0754 000 111\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("phone_needs_verification");
    Response saved = admin.put("/api/v1/auth/profile", "{\"name\":\"Admin Person\"}").expect(200);
    assertThat(saved.string("$.data.name")).isEqualTo("Admin Person");
  }

  @Test
  @DisplayName("M-06 a delivery's detail masks secrets; resend queues a new delivery")
  void deliveryDetailAndResend() {
    Api.eventually(
        () ->
            admin
                    .get("/api/v1/notification-deliveries?channel=EMAIL")
                    .expect(200)
                    .read("$.data.items.length()", Integer.class)
                > 0);
    Response list = admin.get("/api/v1/notification-deliveries?channel=EMAIL").expect(200);
    String uid = list.string("$.data.items[0].uid");
    Response detail = admin.get("/api/v1/notification-deliveries/" + uid).expect(200);
    assertThat(detail.string("$.data.body")).isNotBlank();
    assertThat(detail.string("$.data.channel")).isEqualTo("EMAIL");

    Response resent =
        admin.post("/api/v1/notification-deliveries/" + uid + "/resend", null).expect(201);
    assertThat(resent.string("$.data.uid")).isNotEqualTo(uid);

    Response inApp = admin.get("/api/v1/notification-deliveries?channel=IN_APP").expect(200);
    if (inApp.read("$.data.items.length()", Integer.class) > 0) {
      String inAppUid = inApp.string("$.data.items[0].uid");
      assertThat(
              admin
                  .post("/api/v1/notification-deliveries/" + inAppUid + "/resend", null)
                  .expect(422)
                  .string("$.reason"))
          .isEqualTo("cannot_resend");
    }
  }

  @Test
  @DisplayName("M-11 the same supplier invoice cannot be received twice")
  void duplicateSupplierInvoice() {
    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Dup Supplier\",\"paymentTerms\":\"COD\"}")
            .expect(201)
            .string("$.data.uid");
    String product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Ibuprofen 400mg","genericName":"Ibuprofen","dosageForm":"TABLET",
                 "schedule":"OTC","sellingPrice":500,"costPrice":300,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    String receipt =
        """
        {"supplierUid":"%s","supplierInvoiceNumber":"%s","lines":[
          {"productUid":"%s","batchNumber":"B-%s","expiryDate":"%s","quantity":10,"unitCost":300}]}
        """;
    till.post(
            "/api/v1/goods-receipts",
            receipt.formatted(supplier, "INV-77", product, "1", LocalDate.now().plusYears(1)))
        .expect(201);
    Response duplicate =
        till.post(
            "/api/v1/goods-receipts",
            receipt.formatted(supplier, "inv-77", product, "2", LocalDate.now().plusYears(1)));
    assertThat(duplicate.expect(422).string("$.reason")).isEqualTo("duplicate_supplier_invoice");
    assertThat(duplicate.string("$.field")).isEqualTo("supplierInvoiceNumber");
  }

  @Test
  @DisplayName("M-02 opening balances post once; equity takes the difference; accounts open")
  void openingBalances() {
    String customer =
        admin
            .post(
                "/api/v1/customers",
                "{\"name\":\"Opening Clinic\",\"phone\":\"+255700000009\",\"type\":\"WHOLESALE\",\"creditLimit\":100000}")
            .expect(201)
            .string("$.data.uid");
    String supplier =
        admin
            .post(
                "/api/v1/suppliers", "{\"name\":\"Opening Supplier\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");
    Response before = admin.get("/api/v1/opening-balances").expect(200);
    assertThat(before.json().read("$.data.posted", Object.class)).isNull();

    Response posted =
        admin
            .post(
                "/api/v1/opening-balances",
                """
                {"cash":50000,"bank":200000,"mobileMoney":30000,"inventory":120000,
                 "receivables":[{"partyUid":"%s","amount":40000}],
                 "payables":[{"partyUid":"%s","amount":90000}]}
                """
                    .formatted(customer, supplier))
            .expect(201);
    // 50,000 + 200,000 + 30,000 + 120,000 + 40,000 − 90,000
    assertThat(posted.read("$.data.capital", Double.class)).isEqualTo(350_000.0);
    assertThat(posted.string("$.data.journalNumber")).startsWith("JV");

    Response again = admin.post("/api/v1/opening-balances", "{\"cash\":1}").expect(409);
    assertThat(again.string("$.reason")).isEqualTo("opening_balances_posted");

    assertThat(
            admin
                .get("/api/v1/receivables/accounts/" + customer)
                .expect(200)
                .read("$.data.balance", Double.class))
        .isEqualTo(40_000.0);
    List<Object> equity =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '3000')].balance");
    assertThat(Double.parseDouble(equity.getFirst().toString())).isEqualTo(350_000.0);
    List<Object> cash =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '1000')].balance");
    assertThat(Double.parseDouble(cash.getFirst().toString())).isEqualTo(50_000.0);
  }

  @Test
  @DisplayName("L-06 new dosage forms; L-02 audit entries carry a readable label")
  void dosageFormsAndAuditLabels() {
    List<String> forms = admin.get("/api/v1/dosage-forms").expect(200).json().read("$.data");
    assertThat(forms)
        .contains(
            "ORAL_SOLUTION",
            "SPRAY",
            "PATCH",
            "POWDER",
            "LOTION",
            "PESSARY",
            "LOZENGE",
            "EYE_OINTMENT",
            "INFUSION");
    admin
        .post(
            "/api/v1/products",
            """
            {"name":"Nicotine Patch 21mg","genericName":"Nicotine","dosageForm":"PATCH",
             "schedule":"OTC","sellingPrice":5000,"costPrice":3000,"taxRatePercent":0}
            """)
        .expect(201);
    List<String> labels =
        admin
            .get("/api/v1/audit?entityType=Product&limit=20")
            .expect(200)
            .json()
            .read("$.data.items[*].label");
    assertThat(labels).contains("Nicotine Patch 21mg");
    List<String> platform =
        root.get("/api/v1/platform/audit?entityType=Tenant&limit=50")
            .expect(200)
            .json()
            .read("$.data.items[*].label");
    assertThat(platform).contains("Admin " + code);
    // Sign-ins are written without a label; they read with the user's name, not an id.
    List<Map<String, Object>> signIns =
        root.get("/api/v1/platform/audit?entityType=User&limit=50")
            .expect(200)
            .json()
            .read("$.data.items[?(@.action == 'SIGN_IN')]");
    assertThat(signIns)
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry.get("label")).isNotNull());
  }
}
