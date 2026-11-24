package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import tz.co.pharmacy.modules.access.ports.inbound.PharmacyVisits;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditConfiguration.AuditPartitions;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Batch 10, operations: audit entries carry the client's address, user agent, session and outcome,
 * every export and every root request is audited (STD-22, FRD-19); the owners' daily digest of
 * sensitive actions (FRD-19); the default audit partition is watched and repaired (OPS-18); a
 * member's last activity is written once per period whichever instance serves them; the till's
 * abandoned carts reach the server and its report (FRD-20).
 */
class OperationsLeftoversTest extends IntegrationTest {

  @Autowired ApplicationContext context;
  @Autowired DataSource dataSource;
  @Autowired AuditPartitions partitions;
  @Autowired MeterRegistry meters;
  @Autowired TenantService tenantService;
  @Autowired PharmacyVisits visits;

  private Api anonymous;
  private Api root;
  private Api admin;
  private Api till;
  private String tenant;
  private String email;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "OL" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Ops %s","legalName":"Ops %s Ltd","country":"TZ",
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
  @DisplayName(
      "audit entries carry IP, user agent, session and outcome; every export and root request is"
          + " audited")
  void auditFieldsExportsAndRoot() {
    String agent = "PharmacyTest/1.0 (+till 3)";
    admin.get("/api/v1/products/export?format=csv", "User-Agent", agent).expect(200);

    Response exported = admin.get("/api/v1/audit?action=DATA_EXPORTED").expect(200);
    assertThat(exported.read("$.data.items.length()", Integer.class)).isEqualTo(1);
    assertThat(exported.string("$.data.items[0].userAgent")).isEqualTo(agent);
    assertThat(exported.string("$.data.items[0].ip")).isNotBlank();
    assertThat(exported.string("$.data.items[0].sessionUid")).isNotBlank();
    assertThat(exported.string("$.data.items[0].outcome")).isEqualTo("SUCCESS");
    assertThat(exported.string("$.data.items[0].after.request"))
        .isEqualTo("GET /api/v1/products/export");
    assertThat(exported.read("$.data.items[0].after.rows", Integer.class)).isZero();

    // Root reads twice within the hour: both are on the pharmacy's record, with their outcome.
    Api support = root.in(tenant, null);
    support.get("/api/v1/settings").expect(200);
    support.get("/api/v1/products/" + UUID.randomUUID()).expect(404);
    Response visits = admin.get("/api/v1/audit?action=ROOT_ACTED_IN_TENANT&limit=100").expect(200);
    List<String> outcomes = visits.json().read("$.data.items[*].outcome");
    List<String> requests = visits.json().read("$.data.items[*].after.request");
    assertThat(requests).contains("GET /api/v1/settings");
    assertThat(outcomes).contains("SUCCESS", "FAILURE");

    // A failed sign-in is a FAILURE, with the address it came from.
    anonymous.post(
        "/api/v1/auth/sign-in", "{\"email\":\"%s\",\"password\":\"wrong-one\"}".formatted(email));
    Response failed = root.get("/api/v1/platform/audit?action=SIGN_IN_FAILED&limit=5").expect(200);
    assertThat(failed.string("$.data.items[0].outcome")).isEqualTo("FAILURE");
    assertThat(failed.string("$.data.items[0].ip")).isNotBlank();
  }

  @Test
  @DisplayName(
      "the daily digest lists the day's sensitive actions to the owners and admins; a pharmacy"
          + " may turn it off")
  void sensitiveDigest() throws Exception {
    String product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Digest Syrup","genericName":"Digest","dosageForm":"SYRUP",
                 "schedule":"OTC","sellingPrice":3000,"costPrice":1000,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"DG-1","expiryDate":"%s","quantity":10,"unitCost":1000}
            """
                .formatted(product, LocalDate.now().plusYears(1)))
        .expect(201);
    List<String> batches =
        till.get("/api/v1/stock/" + product + "/batches").expect(200).json().read("$.data[*].uid");
    till.post(
            "/api/v1/stock/adjustments",
            """
            {"productUid":"%s","batchUid":"%s","quantityChange":-1,"reasonCode":"DAMAGE",
             "note":"Dropped"}
            """
                .formatted(product, batches.getFirst()))
        .expect(201);
    assertThat(
            admin
                .get("/api/v1/audit?action=STOCK_ADJUSTED")
                .expect(200)
                .string("$.data.items[0].after.product"))
        .isEqualTo("Digest Syrup");

    LocalDate today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
    assertThat(digest(today)).isTrue();
    eventually(
        () -> admin.get("/api/v1/notifications?limit=20").body().contains("sensitive actions"));
    String inbox = admin.get("/api/v1/notifications?limit=20").expect(200).body();
    assertThat(inbox).contains("Write-offs, adjustments and destructions: 1");
    // Each action is listed in the email.
    List<String> emails =
        admin
            .get("/api/v1/notification-deliveries?channel=EMAIL&limit=20")
            .expect(200)
            .json()
            .read("$.data.items[?(@.templateKey == 'tenant.sensitive_digest')].uid");
    assertThat(emails).hasSize(1);
    assertThat(
            admin
                .get("/api/v1/notification-deliveries/" + emails.getFirst())
                .expect(200)
                .string("$.data.body"))
        .contains("Digest Syrup");

    admin.put("/api/v1/settings", "{\"sensitiveDigest\":false}").expect(200);
    assertThat(
            admin.get("/api/v1/settings").expect(200).read("$.data.sensitiveDigest", Boolean.class))
        .isFalse();
    assertThat(digest(today)).isFalse();
    // Nothing happened on a day: nothing is sent.
    admin.put("/api/v1/settings", "{\"sensitiveDigest\":true}").expect(200);
    assertThat(digest(today.minusYears(3))).isFalse();
  }

  @Test
  @DisplayName(
      "entries trapped in the default audit partition are counted and moved into their month")
  void defaultPartitionRepair() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID uid = UUID.randomUUID();
    Instant far = Instant.parse("2099-06-15T10:00:00Z");
    jdbc.update(
        "insert into audit.entries (uid, at, actor, action) values (?, ?, 'test', 'TRAPPED')",
        uid,
        java.sql.Timestamp.from(far));
    assertThat(jdbc.queryForObject("select audit.default_partition_rows()", Long.class))
        .isPositive();

    assertThat(partitions.watchDefault()).isPositive();
    assertThat(meters.get("pharmacy.audit.default_partition.rows").gauge().value()).isPositive();
    assertThat(partitions.repair()).isPositive();
    assertThat(jdbc.queryForObject("select audit.default_partition_rows()", Long.class)).isZero();
    assertThat(meters.get("pharmacy.audit.default_partition.rows").gauge().value()).isZero();
    // The entry is still there, now in its month's partition.
    assertThat(
            jdbc.queryForObject(
                "select tableoid::regclass::text from audit.entries where uid = ?",
                String.class,
                uid))
        .isEqualTo("audit.entries_2099_06");
  }

  @Test
  @DisplayName("a member's last activity is written once per period, whichever instance notes it")
  void lastActivityOncePerPeriod() throws Exception {
    String userUid = admin.get("/api/v1/auth/me").expect(200).string("$.data.user.uid");
    UUID user = UUID.fromString(userUid);
    UUID pharmacy = UUID.fromString(tenant);
    visits.working(UUID.randomUUID(), user, pharmacy);
    Instant first = lastActive(userUid);
    assertThat(first).isNotNull();

    // Instance B: the same parts built again, with a memory of its own.
    Object other =
        context
            .getAutowireCapableBeanFactory()
            .createBean(
                Class.forName("tz.co.pharmacy.modules.access.application.PharmacyVisitsImpl"));
    ((PharmacyVisits) other).working(UUID.randomUUID(), user, pharmacy);
    assertThat(lastActive(userUid)).isEqualTo(first);
  }

  @Test
  @DisplayName("the till's cancelled carts and no-sales reach the report per cashier and day")
  void tillEvents() {
    till.post(
            "/api/v1/till-events",
            "{\"kind\":\"CART_CANCELLED\",\"lines\":3,\"value\":4500,\"reason\":\"Customer left\"}")
        .expect(201);
    till.post("/api/v1/till-events", "{\"kind\":\"CART_CANCELLED\",\"lines\":1,\"value\":500}")
        .expect(201);
    till.post("/api/v1/till-events", "{\"kind\":\"NO_SALE\"}").expect(201);
    Response bad = till.post("/api/v1/till-events", "{\"kind\":\"LOST\"}");
    assertThat(bad.expect(422).string("$.reason")).isEqualTo("invalid_event_kind");

    Response report = admin.get("/api/v1/reports/till-events").expect(200);
    List<Map<String, Object>> rows = report.json().read("$.data");
    Map<String, Object> cancelled =
        rows.stream().filter(r -> "CART_CANCELLED".equals(r.get("kind"))).findFirst().orElseThrow();
    assertThat(((Number) cancelled.get("events")).intValue()).isEqualTo(2);
    assertThat(((Number) cancelled.get("lines")).intValue()).isEqualTo(4);
    assertThat(((Number) cancelled.get("value")).doubleValue()).isEqualTo(5000.0);
    assertThat(cancelled.get("cashierName")).isEqualTo("Admin");
    assertThat(rows).anyMatch(r -> "NO_SALE".equals(r.get("kind")));
    assertThat(
            admin
                .get("/api/v1/reports/till-events?from=2025-01-01&to=2026-01-01")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("period_too_long");
  }

  // ------------------------------------------------------------------ helpers

  private Instant lastActive(String userUid) {
    List<String> values =
        admin
            .get("/api/v1/users?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[?(@.userUid == '" + userUid + "')].lastActiveAt");
    return values.isEmpty() || values.getFirst() == null ? null : Instant.parse(values.getFirst());
  }

  /** Runs the digest of {@code day} for the test's pharmacy, as the job does. */
  private boolean digest(LocalDate day) throws Exception {
    Object bean = AopTestUtils.getUltimateTargetObject(context.getBean("sensitiveActionsDigest"));
    Method send = bean.getClass().getDeclaredMethod("send", TenantView.class, LocalDate.class);
    send.setAccessible(true);
    UUID uid = UUID.fromString(tenant);
    TenantView view =
        ExecutionContext.callAs(
            ExecutionContext.Frame.service("test", null, null),
            () -> tenantService.find(uid).orElseThrow());
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service("test", null, uid),
        () ->
            TenantContext.callAs(
                uid,
                () -> {
                  try {
                    return (Boolean) send.invoke(bean, view, day);
                  } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                  }
                }));
  }
}
