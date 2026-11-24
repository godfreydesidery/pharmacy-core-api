package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Totp;

/**
 * The security and privacy leftovers of the adversarial review of 23 Nov 2026 (Batch 10): a
 * prescription scan needs {@code health.view}; a recall shows a branch-limited user only its
 * branches' buyers; the claim batch export needs {@code data.export} and {@code health.view}; and
 * an upload link works once.
 */
class SecurityLeftoversTest extends IntegrationTest {

  private static final String ADMIN_PASSWORD = "Admin#2026x";
  private static final String STAFF_PASSWORD = "Staff#2026x";
  private static final byte[] PDF =
      "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired DataSource dataSource;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired tz.co.pharmacy.modules.notifications.ports.inbound.Notifier notifier;
  @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entities;

  private Api anonymous;
  private Api admin;
  private String tenant;
  private String hq;
  private String second;
  private String product;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SL" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Leftovers %s","legalName":"Leftovers %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"%s"}}
                """
                    .formatted(code, code, code, email, ADMIN_PASSWORD))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, ADMIN_PASSWORD)).in(tenant, null);
    hq = admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    second =
        admin
            .post("/api/v1/branches", "{\"code\":\"B2\",\"name\":\"Arusha\"}")
            .expect(201)
            .string("$.data.uid");
    product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Amoxicillin 250mg","genericName":"Amoxicillin","dosageForm":"CAPSULE",
                 "schedule":"OTC","sellingPrice":1000,"costPrice":500,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    for (String branch : List.of(hq, second)) {
      admin
          .in(tenant, branch)
          .post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"LOT-R","expiryDate":"%s","quantity":50,"unitCost":500}
              """
                  .formatted(product, LocalDate.now().plusYears(1)))
          .expect(201);
    }
  }

  // ------------------------------------------------------------- prescription scans

  @Test
  @DisplayName(
      "a prescription scan is listed, linked and downloaded only with health.view, audited")
  void prescriptionScansNeedHealthView() {
    String sale = sell(admin.in(tenant, hq), null).string("$.data.uid");
    String scan =
        admin
            .in(tenant, hq)
            .upload(
                "/api/v1/files?ownerType=SALE&ownerUid=" + sale + "&purpose=PRESCRIPTION",
                "rx.pdf",
                "application/pdf",
                PDF)
            .expect(201)
            .string("$.data.uid");
    String customer =
        admin
            .post("/api/v1/customers", "{\"name\":\"Rehema Juma\",\"phone\":\"0754000111\"}")
            .expect(201)
            .string("$.data.uid");
    String customerRx =
        admin
            .upload(
                "/api/v1/files?ownerType=CUSTOMER&ownerUid=" + customer + "&purpose=PRESCRIPTION",
                "old-rx.pdf",
                "application/pdf",
                PDF)
            .expect(201)
            .string("$.data.uid");
    String customerCard =
        admin
            .upload(
                "/api/v1/files?ownerType=CUSTOMER&ownerUid=" + customer + "&purpose=ATTACHMENT",
                "id-card.pdf",
                "application/pdf",
                PDF)
            .expect(201)
            .string("$.data.uid");

    // A cashier sees sales and customers, not health details.
    Api cashier = login("CASHIER", null).in(tenant, hq);
    for (String path :
        List.of(
            "/api/v1/files?ownerType=SALE&ownerUid=" + sale,
            "/api/v1/files/" + scan + "/url",
            "/api/v1/files/" + scan + "/download",
            "/api/v1/files/" + customerRx + "/url")) {
      assertThat(cashier.get(path).expect(403).string("$.reason")).as(path).isEqualTo("forbidden");
    }
    // A customer's other files stay theirs to see; the prescription among them is left out.
    List<String> listed =
        cashier
            .get("/api/v1/files?ownerType=CUSTOMER&ownerUid=" + customer)
            .expect(200)
            .json()
            .read("$.data[*].uid");
    assertThat(listed).containsExactly(customerCard);
    cashier.get("/api/v1/files/" + customerCard + "/url").expect(200);

    // A pharmacist holds health.view: the scan is theirs to read, and the read is audited.
    Api pharmacist = login("PHARMACIST", null).in(tenant, hq);
    pharmacist.get("/api/v1/files?ownerType=SALE&ownerUid=" + sale).expect(200);
    pharmacist.get("/api/v1/files/" + scan + "/url").expect(200);
    List<String> viewed =
        admin
            .get("/api/v1/audit?action=PRESCRIPTION_FILE_VIEWED&limit=20")
            .expect(200)
            .json()
            .read("$.data.items[*].entityUid");
    assertThat(viewed).contains(scan);
  }

  // ------------------------------------------------------------- recalls

  @Test
  @DisplayName("a recall shows a branch-limited user its own branches' buyers only")
  void recallFollowsTheBranches() {
    sell(admin.in(tenant, hq), "Baraka Moshi").expect(201);
    sell(admin.in(tenant, second), "Neema Kweka").expect(201);
    String batch = batch();

    List<String> everyBranch =
        admin
            .get("/api/v1/recalls/batches/" + batch)
            .expect(200)
            .json()
            .read("$.data.sales[*].branchName");
    assertThat(everyBranch).containsExactlyInAnyOrder("Head Office", "Arusha");

    // A store keeper of the head office: its buyers only, and no patient names (health.view).
    Api keeper = login("STORE_KEEPER", hq).in(tenant, null);
    Response limited = keeper.get("/api/v1/recalls/batches/" + batch).expect(200);
    List<String> branches = limited.json().read("$.data.sales[*].branchName");
    assertThat(branches).containsExactly("Head Office");
    List<Object> patients = limited.json().read("$.data.sales[*].patientName");
    assertThat(patients).containsOnlyNulls();
    // The manager of the head office reads the patient's name.
    Api manager = login("BRANCH_MANAGER", hq).in(tenant, null);
    List<String> named =
        manager
            .get("/api/v1/recalls/batches/" + batch)
            .expect(200)
            .json()
            .read("$.data.sales[*].patientName");
    assertThat(named).containsExactly("Baraka Moshi");
  }

  // ------------------------------------------------------------- claim batch export

  @Test
  @DisplayName("the claim batch export needs data.export and health.view, not finance.view alone")
  void claimExportNeedsHealthAndExport() {
    String missing = "/api/v1/insurance/batches/" + UUID.randomUUID() + "/export";
    // An accountant holds finance.view and data.export, not health.view: claims carry diagnoses.
    Api accountant = login("ACCOUNTANT", null).in(tenant, null);
    assertThat(accountant.get(missing).expect(403).string("$.reason")).isEqualTo("forbidden");
    // Past the permissions, the batch itself is looked for.
    assertThat(admin.get(missing).status()).isEqualTo(404);
  }

  // ------------------------------------------------------------- one-time upload links

  @Test
  @DisplayName("an upload link stores its bytes once: a second PUT is refused, even when complete")
  void uploadLinkWorksOnce() throws Exception {
    Response ticket =
        admin
            .post(
                "/api/v1/files/uploads",
                """
                {"name":"invoice.pdf","contentType":"application/pdf","size":%d,
                 "ownerType":"TENANT","ownerUid":"%s","purpose":"ATTACHMENT"}
                """
                    .formatted(PDF.length, tenant))
            .expect(201);
    String uploadUrl = ticket.string("$.data.uploadUrl");
    String fileUid = ticket.string("$.data.fileUid");

    assertThat(put(uploadUrl, PDF)).isEqualTo(200);
    byte[] other = "%PDF-1.4\nother bytes\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    assertThat(put(uploadUrl, other)).isEqualTo(409);
    admin.post("/api/v1/files/uploads/" + fileUid + "/complete", null).expect(200);
    assertThat(put(uploadUrl, other)).isEqualTo(409);
    String url = admin.get("/api/v1/files/" + fileUid + "/url").expect(200).string("$.data.url");
    byte[] stored =
        HTTP.send(
                HttpRequest.newBuilder(
                        URI.create(url.startsWith("/") ? "http://localhost:" + port + url : url))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray())
            .body();
    assertThat(stored).isEqualTo(PDF);
  }

  // ------------------------------------------------------------- AUTH-16: keyed code hashes

  @Test
  @DisplayName("recovery codes are kept as keyed hashes; a code kept as SHA-256 before still works")
  void recoveryCodesAreKeyed() throws Exception {
    String signIn = loginName("CASHIER");
    Api staff = anonymous.as(anonymous.signIn(signIn, STAFF_PASSWORD));
    String secret = staff.post("/api/v1/auth/2fa/setup", "{}").expect(200).string("$.data.secret");
    List<String> codes =
        staff
            .post(
                "/api/v1/auth/2fa/enable",
                "{\"code\":\"" + Totp.code(secret, java.time.Instant.now()) + "\"}")
            .expect(200)
            .json()
            .read("$.data.recoveryCodes");
    assertThat(codes).hasSize(10);
    String userUid =
        jdbc()
            .queryForObject(
                "select uid::text from access.users where lower(email) = lower(?)",
                String.class,
                signIn);
    List<String> stored =
        jdbc()
            .queryForList(
                "select code_hash from access.recovery_codes where user_uid = ?::uuid",
                String.class,
                userUid);
    assertThat(stored).hasSize(10).allMatch(hash -> hash.startsWith("h1:"));
    String bare =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        ("recovery:" + codes.getFirst().replace("-", ""))
                            .getBytes(StandardCharsets.UTF_8)));
    assertThat(stored).doesNotContain(bare);

    // A code stored before this release, as its SHA-256, still signs in: nobody is locked out.
    String legacyCode = "abcd-efgh";
    String legacy =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("recovery:abcdefgh".getBytes(StandardCharsets.UTF_8)));
    jdbc()
        .update(
            "insert into access.recovery_codes (uid, user_uid, code_hash, created_at, created_by)"
                + " values (gen_random_uuid(), ?::uuid, ?, now(), 'test')",
            userUid,
            legacy);
    verify(signIn, legacyCode).expect(201);
    assertThat(verify(signIn, legacyCode).status()).isNotEqualTo(201);
    verify(signIn, codes.get(1)).expect(201);
  }

  private Response verify(String signIn, String code) {
    Response challenge =
        anonymous
            .post(
                "/api/v1/auth/sign-in",
                "{\"email\":\"" + signIn + "\",\"password\":\"" + STAFF_PASSWORD + "\"}")
            .expect(202);
    return anonymous.post(
        "/api/v1/auth/sign-in/verify",
        "{\"challengeToken\":\"%s\",\"code\":\"%s\"}"
            .formatted(challenge.string("$.data.challengeToken"), code));
  }

  // ------------------------------------------------------------- AUTH-8: queued account messages

  @Test
  @DisplayName("a queued account message holds its link sealed; sent, the link is delivered")
  void queuedAccountMessagesAreSealed() {
    String address = "sealed." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    String token = "prt_" + UUID.randomUUID().toString().replace("-", "");
    String link = "https://app.example/reset-password?token=" + token;
    Map<String, Object> queued =
        tz.co.pharmacy.platform.kernel.ExecutionContext.callAs(
            tz.co.pharmacy.platform.kernel.ExecutionContext.Frame.service("test", null, null),
            () ->
                new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(
                        status -> {
                          notifier.send(
                              new tz.co.pharmacy.modules.notifications.ports.inbound
                                  .NotificationRequest(
                                  "access.password_reset",
                                  new tz.co.pharmacy.modules.notifications.ports.inbound.Recipient(
                                      null, "Amina", address, null, "en"),
                                  java.util.Set.of(
                                      tz.co.pharmacy.modules.notifications.ports.inbound.Channel
                                          .EMAIL),
                                  Map.of("link", link, "minutes", 30),
                                  null));
                          entities.flush();
                          // Before the commit, nothing has been sent: this is the row at rest.
                          return jdbc()
                              .queryForMap(
                                  "select body, html_body, sealed from notifications.messages"
                                      + " where to_address = ?",
                                  address);
                        }));
    assertThat(queued.get("sealed")).isEqualTo(true);
    assertThat((String) queued.get("body")).doesNotContain(token).doesNotContain("reset-password");
    assertThat(String.valueOf(queued.get("html_body"))).doesNotContain(token);

    // The sender opened it: the test profile keeps sent account messages, so the link is there.
    Api.eventually(
        () ->
            jdbc()
                .queryForObject(
                    "select body from notifications.messages where to_address = ?",
                    String.class,
                    address)
                .contains(token));
  }

  // ------------------------------------------------------------- ISO-11: the event registry

  @Test
  @DisplayName(
      "the event registry keeps no voucher code, and the application cannot delete from it")
  void eventRegistryKeepsNoSecrets() {
    Response voucher =
        admin
            .in(tenant, hq)
            .post("/api/v1/vouchers", "{\"value\":5000,\"paidBy\":\"CASH\"}")
            .expect(201);
    String code = voucher.string("$.data.code");
    String uid = voucher.string("$.data.uid");
    String event =
        Api.eventuallyValue(
            () ->
                jdbc()
                    .queryForList(
                        "select serialized_event from platform.event_publication"
                            + " where serialized_event like ?",
                        String.class,
                        "%" + uid + "%")
                    .stream()
                    .findFirst()
                    .orElse(null));
    assertThat(event).doesNotContain(code).contains(code.substring(code.length() - 4));
    // Modulith needs select, insert and update; purging and erasure run as the owner.
    assertThat(
            jdbc()
                .queryForObject(
                    "select has_table_privilege('platform.event_publication', 'DELETE')",
                    Boolean.class))
        .isFalse();
    assertThat(
            jdbc()
                .queryForObject(
                    "select has_table_privilege('platform.event_publication_archive', 'SELECT')",
                    Boolean.class))
        .isFalse();
    assertThat(
            jdbc()
                .queryForObject(
                    "select has_table_privilege('platform.event_publication', 'UPDATE')",
                    Boolean.class))
        .isTrue();
  }

  // ------------------------------------------------------------- helpers

  private Response sell(Api till, String patient) {
    String prescription =
        patient == null
            ? ""
            : """
              ,"prescription":{"prescriberName":"Dr Asha","patientName":"%s","prescriptionDate":"%s"}
              """
                .formatted(patient, LocalDate.now().minusDays(1));
    return till.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
         "payments":[{"method":"CASH","amount":1000}]%s}
        """
            .formatted(UUID.randomUUID(), product, prescription));
  }

  private String batch() {
    List<Map<String, Object>> batches =
        admin
            .get("/api/v1/recalls/products/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[*]");
    return (String) batches.getFirst().get("uid");
  }

  /** A staff login with {@code roleCode}, for every branch or {@code branch} only, signed in. */
  private Api login(String roleCode, String branch) {
    return anonymous.as(anonymous.signIn(loginName(roleCode, branch), STAFF_PASSWORD));
  }

  private String loginName(String roleCode) {
    return loginName(roleCode, null);
  }

  /** A staff login with {@code roleCode} and its own password ({@link #STAFF_PASSWORD}). */
  private String loginName(String roleCode, String branch) {
    List<String> roles =
        admin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + roleCode + "')].uid");
    String username = "staff." + UUID.randomUUID().toString().substring(0, 8);
    Response created =
        admin
            .post(
                "/api/v1/users/managed",
                """
                {"username":"%s","name":"Staff %s","roleUid":"%s",%s}
                """
                    .formatted(
                        username,
                        username,
                        roles.getFirst(),
                        branch == null
                            ? "\"allBranches\":true"
                            : "\"allBranches\":false,\"branchUids\":[\"" + branch + "\"]"))
            .expect(201);
    String signIn = created.string("$.data.signIn");
    String temporary = created.string("$.data.temporaryPassword");
    anonymous
        .as(anonymous.signIn(signIn, temporary))
        .post(
            "/api/v1/auth/change-password",
            "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                .formatted(temporary, STAFF_PASSWORD))
        .expect(200);
    return signIn;
  }

  private int put(String path, byte[] body) throws InterruptedException {
    try {
      return HTTP.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                  .header("Content-Type", "application/pdf")
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                  .build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    } catch (IOException closed) {
      return -1;
    }
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }
}
