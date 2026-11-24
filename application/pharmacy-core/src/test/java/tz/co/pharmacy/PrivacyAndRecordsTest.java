package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Staff;

/**
 * Batch 9 (adversarial review STD-5, STD-7, STD-8): personal-data rights of one customer (export,
 * correction, erasure by anonymisation, consent, the request register), health details shown only
 * with health.view, the dispensing rules for prescription medicines, the TMDA registration number
 * and adverse drug reaction reports.
 */
class PrivacyAndRecordsTest extends IntegrationTest {

  private static final String PASSWORD = "Staff#2026x";

  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api root;
  private Api admin;
  private Api hq;
  private Api cashier;
  private Api pharmacist;
  private String tenant;
  private String amoxicillin;
  private String morphine;
  private String paracetamol;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "PR" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Privacy %s","legalName":"Privacy %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Neema Owner","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    hq = admin.in(tenant, branch);
    Staff.Login till = Staff.login(admin, anonymous, "Asha Cashier", "CASHIER", PASSWORD);
    cashier = anonymous.as(anonymous.signIn(till.signIn(), PASSWORD)).in(tenant, branch);
    Staff.Login rx = Staff.login(admin, anonymous, "Juma Pharmacist", "PHARMACIST", PASSWORD);
    pharmacist = anonymous.as(anonymous.signIn(rx.signIn(), PASSWORD)).in(tenant, branch);

    amoxicillin = product("Amoxicillin 500mg", "PRESCRIPTION", "TMDA/MD/0001");
    morphine = product("Morphine 10mg", "CONTROLLED", null);
    paracetamol = product("Paracetamol 500mg", "OTC", null);
    for (String product : List.of(amoxicillin, morphine, paracetamol)) {
      hq.post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"B-%s","expiryDate":"%s","quantity":50,
               "unitCost":500}
              """
                  .formatted(product, product.substring(0, 6), LocalDate.now().plusYears(1)))
          .expect(201);
    }
  }

  // ------------------------------------------------------------------ STD-7 dispensing

  @Test
  @DisplayName(
      "a prescription medicine is sold by someone allowed to dispense, against a whole, current"
          + " prescription, and the dispenser is recorded")
  void dispensing() {
    // The presets: the Pharmacist dispenses and reads health details; the Cashier does neither.
    assertThat(permissions("PHARMACIST"))
        .contains("sales.dispense_rx", "health.view", "adr.report");
    assertThat(permissions("CASHIER"))
        .doesNotContain("sales.dispense_rx", "health.view", "adr.report");
    assertThat(permissions("BRANCH_MANAGER"))
        .contains("sales.dispense_rx", "health.view", "adr.report");

    String yesterday = LocalDate.now().minusDays(1).toString();
    String full = prescription("Dr. Mushi", "MCT-1180", "Baraka Moshi", yesterday);
    Response refused = sell(cashier, amoxicillin, null, full);
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("dispense_not_allowed");
    assertThat(refused.string("$.products")).isEqualTo("Amoxicillin 500mg");
    // An OTC medicine needs neither.
    sell(cashier, paracetamol, null, null).expect(201);

    Response noRegistration =
        sell(pharmacist, amoxicillin, null, prescription("Dr. Mushi", "", "Baraka", yesterday));
    assertThat(noRegistration.expect(422).string("$.reason")).isEqualTo("prescription_incomplete");
    assertThat(noRegistration.string("$.field")).isEqualTo("prescription.prescriberRegistration");
    Response noPatient =
        sell(pharmacist, amoxicillin, null, prescription("Dr. Mushi", "MCT-1", null, yesterday));
    assertThat(noPatient.expect(422).string("$.field")).isEqualTo("prescription.patientName");
    Response noDate =
        sell(pharmacist, amoxicillin, null, prescription("Dr. Mushi", "MCT-1", "Baraka", null));
    assertThat(noDate.expect(422).string("$.field")).isEqualTo("prescription.prescriptionDate");
    Response future =
        sell(
            pharmacist,
            amoxicillin,
            null,
            prescription("Dr. Mushi", "MCT-1", "Baraka", LocalDate.now().plusDays(3).toString()));
    assertThat(future.expect(422).string("$.reason")).isEqualTo("prescription_date_in_future");
    Response old =
        sell(
            pharmacist,
            amoxicillin,
            null,
            prescription(
                "Dr. Mushi", "MCT-1", "Baraka", LocalDate.now().minusMonths(7).toString()));
    assertThat(old.expect(422).string("$.reason")).isEqualTo("prescription_expired");
    assertThat(old.read("$.validMonths", Integer.class)).isEqualTo(6);

    Response sold = sell(pharmacist, amoxicillin, null, full).expect(201);
    assertThat(sold.string("$.data.dispensedBy")).isEqualTo("Juma Pharmacist");
    assertThat(sold.string("$.data.prescription.prescriptionDate")).isEqualTo(yesterday);
    String saleUid = sold.string("$.data.uid");

    // Health details: the cashier sees that there is a prescription, not what it says.
    Response seenByCashier = cashier.get("/api/v1/sales/" + saleUid).expect(200);
    assertThat(seenByCashier.read("$.data.prescriptionWithheld", Boolean.class)).isTrue();
    assertThat((Object) seenByCashier.json().read("$.data.prescription")).isNull();
    assertThat(seenByCashier.body()).doesNotContain("Baraka Moshi", "MCT-1180");
    Response seenByPharmacist = pharmacist.get("/api/v1/sales/" + saleUid).expect(200);
    assertThat(seenByPharmacist.string("$.data.prescription.patientName"))
        .isEqualTo("Baraka Moshi");
    assertThat(seenByPharmacist.read("$.data.prescriptionWithheld", Boolean.class)).isFalse();
    // Reading a prescription's detail is audited.
    eventually(
        () ->
            admin
                    .get("/api/v1/audit?action=PRESCRIPTION_VIEWED&entityUid=" + saleUid)
                    .expect(200)
                    .read("$.data.items.length()", Integer.class)
                >= 1);

    // The controlled-drugs register: names only with health.view.
    String controlled =
        sell(
                pharmacist,
                morphine,
                null,
                """
                {"prescriberName":"Dr. Mushi","prescriberRegistration":"MCT-1180",
                 "reference":"KCMC-77","patientName":"Baraka Moshi","prescriptionDate":"%s"}
                """
                    .formatted(yesterday))
            .expect(201)
            .string("$.data.number");
    String register = "/api/v1/controlled-drugs/" + morphine + "/register";
    Response hidden = cashier.get(register).expect(200);
    String sale = "$.data.items[?(@.reference == '" + controlled + "')]";
    assertThat(hidden.read(sale + ".withheld", List.class)).containsExactly(true);
    assertThat(hidden.read(sale + ".patientName", List.class)).containsOnlyNulls();
    Response shown = pharmacist.get(register).expect(200);
    assertThat(shown.read(sale + ".patientName", List.class)).containsExactly("Baraka Moshi");
    assertThat(shown.read(sale + ".dispensedBy", List.class)).containsExactly("Juma Pharmacist");
  }

  @Test
  @DisplayName("the controlled-drugs register's products come a page at a time")
  void controlledListIsPaged() {
    product("Codeine 30mg", "CONTROLLED", null);
    product("Pethidine 50mg", "CONTROLLED", null);
    Response first = hq.get("/api/v1/controlled-drugs?limit=2").expect(200);
    assertThat(first.read("$.data.items[*].name", List.class))
        .containsExactly("Codeine 30mg", "Morphine 10mg");
    String next = first.string("$.data.nextCursor");
    assertThat(next).isNotNull();
    Response second = hq.get("/api/v1/controlled-drugs?limit=2&cursor=" + next).expect(200);
    assertThat(second.read("$.data.items[*].name", List.class)).containsExactly("Pethidine 50mg");
    assertThat((Object) second.json().read("$.data.nextCursor")).isNull();
  }

  // ------------------------------------------------------------------ STD-5 personal data

  @Test
  @DisplayName(
      "consent is recorded per purpose with who and when, and an SMS receipt goes only where the"
          + " customer did not withdraw it")
  void consents() {
    admin.put("/api/v1/settings", "{\"smsReceipts\":true}").expect(200);
    String withdrawn =
        customer("Rehema Said", "0712000201", "{\"SMS_RECEIPTS\":false,\"MARKETING\":true}");
    String silent = customer("Musa Ali", "0712000202", null);

    Response consents = admin.get("/api/v1/customers/" + withdrawn + "/consents").expect(200);
    assertThat(consents.read("$.data.current[?(@.purpose == 'SMS_RECEIPTS')].status", List.class))
        .containsExactly("WITHDRAWN");
    assertThat(consents.read("$.data.current[?(@.purpose == 'MARKETING')].status", List.class))
        .containsExactly("GIVEN");
    assertThat(
            consents.read(
                "$.data.current[?(@.purpose == 'INSURER_SHARING')].effective", List.class))
        .containsExactly(false);
    assertThat(consents.read("$.data.history[*].givenBy", List.class)).contains("Neema Owner");
    Response none = admin.get("/api/v1/customers/" + silent + "/consents").expect(200);
    assertThat(none.read("$.data.current[?(@.purpose == 'SMS_RECEIPTS')].effective", List.class))
        .containsExactly(true);

    // Marketing withdrawn: the same row gets its withdrawal, with who did it.
    Response changed =
        admin
            .post(
                "/api/v1/customers/" + withdrawn + "/consents",
                "{\"purpose\":\"MARKETING\",\"given\":false,\"note\":\"Asked at the counter\"}")
            .expect(200);
    assertThat(changed.read("$.data.current[?(@.purpose == 'MARKETING')].status", List.class))
        .containsExactly("WITHDRAWN");
    assertThat(changed.read("$.data.history[?(@.purpose == 'MARKETING')].withdrawnBy", List.class))
        .containsExactly("Neema Owner");
    assertThat(
            cashier
                .post(
                    "/api/v1/customers/" + withdrawn + "/consents",
                    "{\"purpose\":\"MARKETING\",\"given\":true}")
                .expect(403)
                .string("$.reason"))
        .isEqualTo("forbidden");

    String refusedSale = sell(hq, paracetamol, withdrawn, null).expect(201).string("$.data.uid");
    sell(hq, paracetamol, silent, null).expect(201);
    eventually(() -> receipts("+255712000202") == 1);
    // The reaction to the first sale has run, and sent nothing.
    eventually(() -> notificationReactionDone(refusedSale));
    assertThat(receipts("+255712000201")).isZero();
  }

  @Test
  @DisplayName(
      "a person's data comes out as a ZIP, a correction is logged, and the request register"
          + " keeps the 30-day due date")
  void exportAndCorrection() throws IOException {
    String customer = customer("Baraka Moshi", "0712000301", null);
    sell(
            pharmacist,
            amoxicillin,
            customer,
            prescription(
                "Dr. Mushi", "MCT-1180", "Baraka Moshi", LocalDate.now().minusDays(1).toString()))
        .expect(201);

    LocalDate received = LocalDate.now().minusDays(2);
    Response logged =
        admin
            .post(
                "/api/v1/privacy/requests",
                "{\"kind\":\"ACCESS\",\"customerUid\":\"%s\",\"receivedOn\":\"%s\"}"
                    .formatted(customer, received))
            .expect(201);
    String request = logged.string("$.data.uid");
    assertThat(logged.string("$.data.status")).isEqualTo("OPEN");
    assertThat(logged.string("$.data.number")).startsWith("DSR-");
    assertThat(LocalDate.parse(logged.string("$.data.dueOn")))
        .isBetween(received.plusDays(29), received.plusDays(30));
    assertThat(
            admin
                .post(
                    "/api/v1/privacy/requests",
                    "{\"kind\":\"ACCESS\",\"customerUid\":\"%s\",\"receivedOn\":\"%s\"}"
                        .formatted(customer, LocalDate.now().plusDays(2)))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_received_on");

    // Needs data.export and health.view: the cashier and the pharmacist cannot take it out.
    assertThat(
            cashier
                .post("/api/v1/customers/" + customer + "/data-export", null)
                .expect(403)
                .string("$.reason"))
        .isEqualTo("forbidden");
    pharmacist.post("/api/v1/customers/" + customer + "/data-export", null).expect(403);

    byte[] zip =
        admin.downloadPost(
            "/api/v1/customers/" + customer + "/data-export?requestUid=" + request, null);
    Map<String, String> files = unzip(zip);
    assertThat(files)
        .containsKeys(
            "subject.json",
            "customer.csv",
            "consents.csv",
            "sales.csv",
            "sale_lines.csv",
            "prescriptions.csv",
            "insurance_memberships.csv",
            "insurance_claims.csv",
            "receivable_entries.csv",
            "messages.csv",
            "adverse_reactions.csv",
            "requests.csv");
    assertThat(files.get("customer.csv")).contains("Baraka Moshi").doesNotContain("tenant_uid");
    assertThat(files.get("prescriptions.csv")).contains("MCT-1180", "Juma Pharmacist");
    assertThat(files.get("sale_lines.csv")).contains("Amoxicillin 500mg");
    assertThat(files.get("subject.json")).contains("\"customerUid\"", customer);

    Response done = admin.get("/api/v1/privacy/requests/" + request).expect(200);
    assertThat(done.string("$.data.status")).isEqualTo("COMPLETED");
    assertThat(done.string("$.data.completedBy")).isEqualTo("Neema Owner");
    eventually(
        () ->
            admin
                    .get("/api/v1/audit?action=DATA_SUBJECT_EXPORT&entityUid=" + customer)
                    .expect(200)
                    .read("$.data.items.length()", Integer.class)
                == 1);
    // A request is answered once.
    assertThat(
            admin
                .post("/api/v1/customers/" + customer + "/data-export?requestUid=" + request, null)
                .expect(409)
                .string("$.reason"))
        .isEqualTo("request_closed");

    // A correction is the customer edit, logged as a request of its own.
    admin
        .put(
            "/api/v1/customers/" + customer + "?dataSubjectRequest=true",
            "{\"name\":\"Baraka Moshi\",\"phone\":\"0712000399\"}")
        .expect(200);
    Response corrections =
        admin.get("/api/v1/privacy/requests?kind=CORRECTION&customerUid=" + customer).expect(200);
    assertThat(corrections.read("$.data.items[*].status", List.class)).containsExactly("COMPLETED");
    assertThat(corrections.string("$.data.items[0].outcome")).contains("phone");

    // An erasure request answered by an access action does not fit; a refusal closes it.
    String erasure =
        admin
            .post(
                "/api/v1/privacy/requests",
                "{\"kind\":\"ERASURE\",\"customerUid\":\"%s\"}".formatted(customer))
            .expect(201)
            .string("$.data.uid");
    assertThat(
            admin
                .post("/api/v1/customers/" + customer + "/data-export?requestUid=" + erasure, null)
                .expect(422)
                .string("$.reason"))
        .isEqualTo("request_mismatch");
    Response refused =
        admin
            .post(
                "/api/v1/privacy/requests/" + erasure + "/refuse",
                "{\"reason\":\"Prescriptions must be kept (legal retention).\"}")
            .expect(200);
    assertThat(refused.string("$.data.status")).isEqualTo("REFUSED");
    assertThat(
            admin
                .get("/api/v1/privacy/requests?status=OPEN&customerUid=" + customer)
                .expect(200)
                .read("$.data.items.length()", Integer.class))
        .isZero();

    // The bulk customer export is audited too.
    admin.get("/api/v1/customers/export?format=csv").expect(200);
    eventually(
        () ->
            admin
                    .get("/api/v1/audit?action=CUSTOMERS_EXPORTED")
                    .expect(200)
                    .read("$.data.items.length()", Integer.class)
                >= 1);
  }

  @Test
  @DisplayName(
      "erasure anonymises the person and keeps the money; refused while the account has a"
          + " balance or the pharmacy is under a legal hold")
  void anonymisation() {
    String customer =
        admin
            .post(
                "/api/v1/customers",
                """
                {"name":"Zawadi Mrema","phone":"0712000401","email":"zawadi@example.com",
                 "address":"Moshi","insuranceProvider":"NHIF","insuranceNumber":"NH-778",
                 "creditLimit":100000,"type":"RETAIL"}
                """)
            .expect(201)
            .string("$.data.uid");
    Response sale =
        hq.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","customerUid":"%s","balanceToAccount":true,
                 "lines":[{"productUid":"%s","quantity":1}],
                 "payments":[{"method":"CASH","amount":100}],
                 "prescription":%s}
                """
                    .formatted(
                        UUID.randomUUID(),
                        customer,
                        amoxicillin,
                        prescription(
                            "Dr. Mushi",
                            "MCT-1180",
                            "Zawadi Mrema",
                            LocalDate.now().minusDays(1).toString())))
            .expect(201);
    String saleUid = sale.string("$.data.uid");
    String total = sale.string("$.data.total");

    Response owing = admin.post("/api/v1/customers/" + customer + "/anonymise", "{}");
    assertThat(owing.expect(422).string("$.reason")).isEqualTo("subject_has_balance");
    assertThat(owing.read("$.balance", Double.class)).isPositive();
    double balance = owing.read("$.balance", Double.class);
    // The account is settled first.
    admin
        .post(
            "/api/v1/receivables/payments",
            "{\"customerUid\":\"%s\",\"amount\":%s,\"method\":\"CASH\",\"reference\":\"SETTLE\"}"
                .formatted(customer, balance))
        .expect(201);

    // Under a legal hold nothing is erased.
    root.post("/api/v1/platform/tenants/" + tenant + "/legal-hold", "{\"reason\":\"Case 9\"}")
        .expect(200);
    assertThat(
            admin
                .post("/api/v1/customers/" + customer + "/anonymise", "{}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("subject_under_legal_hold");
    root.delete("/api/v1/platform/tenants/" + tenant + "/legal-hold").expect(200);

    // Who may: partners.manage and data.export (the pharmacist manages customers, exports none).
    pharmacist.post("/api/v1/customers/" + customer + "/anonymise", "{}").expect(403);

    Response erased =
        admin
            .post("/api/v1/customers/" + customer + "/anonymise", "{\"note\":\"Asked in person\"}")
            .expect(200);
    assertThat(erased.string("$.data.kind")).isEqualTo("ERASURE");
    assertThat(erased.string("$.data.status")).isEqualTo("COMPLETED");
    assertThat(erased.string("$.data.customerName")).isEqualTo("Anonymised customer");

    Response person = admin.get("/api/v1/customers/" + customer).expect(200);
    assertThat(person.string("$.data.name")).isEqualTo("Anonymised customer");
    assertThat(person.string("$.data.anonymisedAt")).isNotNull();
    assertThat(person.body())
        .doesNotContain("Zawadi", "0712000401", "zawadi@example.com", "NH-778", "Moshi\"");
    // The sale keeps its money; the patient on its prescription is gone.
    Response kept =
        admin.in(tenant, sale.string("$.data.branchUid")).get("/api/v1/sales/" + saleUid);
    assertThat(kept.expect(200).string("$.data.total")).isEqualTo(total);
    assertThat(kept.string("$.data.customerName")).isEqualTo("Anonymised customer");
    assertThat(kept.string("$.data.prescription.patientName")).isEqualTo("Anonymised patient");
    assertThat(kept.string("$.data.prescription.prescriberName")).isEqualTo("Dr. Mushi");
    assertThat(
            admin
                .get("/api/v1/receivables/accounts?q=Zawadi")
                .expect(200)
                .read("$.data.items.length()", Integer.class))
        .isZero();
    // The audit entry of the erasure names no personal value.
    eventually(
        () ->
            admin
                    .get("/api/v1/audit?action=DATA_SUBJECT_ERASURE&entityUid=" + customer)
                    .expect(200)
                    .read("$.data.items.length()", Integer.class)
                == 1);
    assertThat(
            admin
                .get("/api/v1/audit?action=DATA_SUBJECT_ERASURE&entityUid=" + customer)
                .expect(200)
                .body())
        .doesNotContain("Zawadi", "0712000401");

    // Once anonymised, the record is not changed again.
    assertThat(
            admin
                .put("/api/v1/customers/" + customer, "{\"name\":\"Zawadi Mrema\"}")
                .expect(409)
                .string("$.reason"))
        .isEqualTo("customer_anonymised");
    assertThat(
            admin
                .post("/api/v1/customers/" + customer + "/anonymise", "{}")
                .expect(409)
                .string("$.reason"))
        .isEqualTo("customer_anonymised");
  }

  // ------------------------------------------------------------------ STD-8 TMDA

  @Test
  @DisplayName("a product carries its TMDA number; an adverse reaction is reported and exported")
  void tmdaAndAdverseReactions() {
    Response product = admin.get("/api/v1/products/" + amoxicillin).expect(200);
    assertThat(product.string("$.data.tmdaRegistrationNumber")).isEqualTo("TMDA/MD/0001");
    assertThat(admin.get("/api/v1/products/export?format=csv").expect(200).body())
        .contains("TMDA registration", "TMDA/MD/0001");

    String customer = customer("Neema Kweka", "0712000501", null);
    String body =
        """
        {"customerUid":"%s","patientName":"Neema Kweka","patientAge":34,"patientSex":"FEMALE",
         "productUid":"%s","batchNumber":"AMX-9","dose":"500 mg three times a day","route":"Oral",
         "reaction":"Rash and itching on the arms","onsetDate":"%s",
         "seriousness":"%s"%s,"outcome":"RECOVERING"}
        """;
    String onset = LocalDate.now().minusDays(1).toString();
    assertThat(
            cashier
                .post(
                    "/api/v1/adverse-reactions",
                    body.formatted(customer, amoxicillin, onset, "NOT_SERIOUS", ""))
                .expect(403)
                .string("$.reason"))
        .isEqualTo("forbidden");
    assertThat(
            pharmacist
                .post(
                    "/api/v1/adverse-reactions",
                    body.formatted(customer, amoxicillin, onset, "SERIOUS", ""))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("serious_reason_required");
    assertThat(
            pharmacist
                .post(
                    "/api/v1/adverse-reactions",
                    body.formatted(
                        customer, amoxicillin, LocalDate.now().plusDays(2), "NOT_SERIOUS", ""))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("onset_in_future");
    Response reported =
        pharmacist
            .post(
                "/api/v1/adverse-reactions",
                body.formatted(
                    customer,
                    amoxicillin,
                    onset,
                    "SERIOUS",
                    ",\"seriousReason\":\"HOSPITALISATION\""))
            .expect(201);
    String uid = reported.string("$.data.uid");
    assertThat(reported.string("$.data.number")).startsWith("ADR-");
    assertThat(reported.string("$.data.productName")).isEqualTo("Amoxicillin 500mg");
    assertThat(reported.string("$.data.reporterName")).isEqualTo("Juma Pharmacist");

    assertThat(cashier.get("/api/v1/adverse-reactions").expect(403).string("$.reason"))
        .isEqualTo("forbidden");
    assertThat(
            admin
                .get("/api/v1/adverse-reactions")
                .expect(200)
                .read("$.data.items[*].uid", List.class))
        .containsExactly(uid);
    pharmacist.get("/api/v1/adverse-reactions/" + uid).expect(200);
    eventually(
        () ->
            admin
                    .get("/api/v1/audit?action=ADR_VIEWED&entityUid=" + uid)
                    .expect(200)
                    .read("$.data.items.length()", Integer.class)
                >= 1);
    String csv = pharmacist.get("/api/v1/adverse-reactions/" + uid + "/export").expect(200).body();
    assertThat(csv)
        .contains(
            "Section,Field,Value",
            "C. Suspected medicine,Batch number,AMX-9",
            "B. Adverse reaction,Reason for seriousness,HOSPITALISATION");

    // The report's patient goes with the customer's anonymisation; the clinical facts stay.
    admin.post("/api/v1/customers/" + customer + "/anonymise", "{}").expect(200);
    Response after = admin.get("/api/v1/adverse-reactions/" + uid).expect(200);
    assertThat((Object) after.json().read("$.data.patientName")).isNull();
    assertThat(after.string("$.data.reaction")).isEqualTo("Rash and itching on the arms");
  }

  // ------------------------------------------------------------------ helpers

  private String product(String name, String schedule, String tmda) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"%s",
             "sellingPrice":1000,"costPrice":500,"taxRatePercent":0%s}
            """
                .formatted(
                    name,
                    name,
                    schedule,
                    tmda == null ? "" : ",\"tmdaRegistrationNumber\":\"" + tmda + "\""))
        .expect(201)
        .string("$.data.uid");
  }

  private String customer(String name, String phone, String consents) {
    return admin
        .post(
            "/api/v1/customers",
            "{\"name\":\"%s\",\"phone\":\"%s\"%s}"
                .formatted(name, phone, consents == null ? "" : ",\"consents\":" + consents))
        .expect(201)
        .string("$.data.uid");
  }

  private static String prescription(
      String prescriber, String registration, String patient, String date) {
    return "{\"prescriberName\":%s,\"prescriberRegistration\":%s,\"patientName\":%s,\"prescriptionDate\":%s}"
        .formatted(quoted(prescriber), quoted(registration), quoted(patient), quoted(date));
  }

  private static String quoted(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private static Response sell(Api at, String product, String customer, String prescription) {
    return at.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s",%s"lines":[{"productUid":"%s","quantity":1}],
         "payments":[{"method":"CASH","amount":5000}]%s}
        """
            .formatted(
                UUID.randomUUID(),
                customer == null ? "" : "\"customerUid\":\"" + customer + "\",",
                product,
                prescription == null ? "" : ",\"prescription\":" + prescription));
  }

  private List<String> permissions(String role) {
    List<String> found =
        admin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + role + "')].permissions[*]", List.class);
    return found;
  }

  /** The SMS receipts queued to {@code phone} in this pharmacy. */
  private int receipts(String phone) {
    return count(
        "select count(*) from notifications.messages where template_key = 'sale.receipt'"
            + " and channel = 'SMS' and to_address = ?",
        phone);
  }

  /** Whether the notification reaction to the sale's sale.completed has run. */
  private boolean notificationReactionDone(String saleUid) {
    return count(
            "select count(*) from platform.event_publication where completion_date is not null"
                + " and listener_id like '%NotificationReactions%'"
                + " and serialized_event like ?",
            "%" + saleUid + "%")
        > 0;
  }

  private int count(String sql, String argument) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement scope =
          connection.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        scope.setString(1, tenant);
        scope.execute();
      }
      try (PreparedStatement query = connection.prepareStatement(sql)) {
        query.setString(1, argument);
        try (ResultSet rows = query.executeQuery()) {
          rows.next();
          int found = rows.getInt(1);
          connection.commit();
          return found;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, String> unzip(byte[] zip) throws IOException {
    Map<String, String> files = new HashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return files;
  }
}
