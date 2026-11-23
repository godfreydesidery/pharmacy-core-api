package tz.co.pharmacy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.modules.integrations.ports.inbound.Webhooks;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * The outside connections, hardened (adversarial review §3): SMTP provider settings (INT-2), the
 * plan's SMS cap (INT-5), webhook secrets and signing (INT-13) and payments (INT-15). No test
 * reaches a real network: refused hosts are refused before any connection, saved public SMTP
 * providers stay switched off, and receivers listen on loopback.
 */
class IntegrationsHardeningTest extends IntegrationTest {

  private static final String PLATFORM = "/api/v1/platform/notification-providers";
  private static final String MINE = "/api/v1/notification-providers";
  private static final String PUBLIC_IP = "93.184.216.34";

  @Autowired DataSource dataSource;
  @Autowired Notifier notifier;
  @Autowired Webhooks webhooks;

  record Pharmacy(String tenant, String branch, Api admin, Api till) {}

  private Pharmacy pharmacy(Api root, String prefix) {
    Api anonymous = api();
    String code = prefix + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Hardening %s","legalName":"Hardening %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    return new Pharmacy(tenant, branch, admin, admin.in(tenant, branch));
  }

  private Api root() {
    Api anonymous = api();
    return anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
  }

  // ------------------------------------------------------------------------------ INT-2 SMTP

  private static String smtp(String host, String port, boolean withPassword) {
    return """
        {"name":"Own SMTP","provider":"smtp","active":false,
         "settings":{"host":"%s","port":"%s","from":"noreply@pharmacy.test","username":"mailer"}%s}
        """
        .formatted(host, port, withPassword ? ",\"secrets\":{\"password\":\"pa55word-1234\"}" : "");
  }

  @Test
  @DisplayName("INT-2: SMTP hosts must be public, ports are mail ports, a new server drops secrets")
  void smtpProviders() throws SQLException {
    Api root = root();
    Pharmacy p = pharmacy(root, "SM");

    for (String host :
        List.of(
            "10.0.0.5",
            "192.168.1.10",
            "172.20.0.5",
            "169.254.169.254",
            "100.72.1.1",
            "127.0.0.2",
            "localhost",
            "postgres.railway.internal",
            "printer.local",
            "mailhost",
            "smtp://" + PUBLIC_IP)) {
      Response refused = p.admin().post(MINE + "/EMAIL", smtp(host, "587", true));
      assertThat(refused.expect(422).string("$.reason"))
          .as(host)
          .isEqualTo("smtp_host_not_allowed");
      assertThat(refused.string("$.field")).isEqualTo("settings.host");
    }
    for (String port : List.of("1", "22", "80", "443", "5432", "6379", "8080")) {
      Response refused = p.admin().post(MINE + "/EMAIL", smtp(PUBLIC_IP, port, true));
      assertThat(refused.expect(422).string("$.reason"))
          .as(port)
          .isEqualTo("smtp_port_not_allowed");
      assertThat(refused.string("$.field")).isEqualTo("settings.port");
    }
    // The platform root's own providers follow the same rules.
    assertThat(
            root.post(PLATFORM + "/EMAIL", smtp("10.0.0.5", "587", true))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("smtp_host_not_allowed");
    assertThat(
            root.post(PLATFORM + "/EMAIL", smtp(PUBLIC_IP, "3306", true))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("smtp_port_not_allowed");

    // A public host on 587 saves (switched off: no test reaches a real network).
    Response saved = p.admin().post(MINE + "/EMAIL", smtp(PUBLIC_IP, "587", true)).expect(201);
    String uid = saved.string("$.data.uid");
    assertThat(saved.string("$.data.secrets.password")).isEqualTo("••••1234");
    // Same server, no secret given: the password is kept.
    Response renamed = p.admin().put(MINE + "/EMAIL/" + uid, smtp(PUBLIC_IP, "587", false));
    assertThat(renamed.expect(200).string("$.data.secrets.password")).isEqualTo("••••1234");
    // Another port: the password is dropped and must be entered again.
    Response moved = p.admin().put(MINE + "/EMAIL/" + uid, smtp(PUBLIC_IP, "465", false));
    assertThat(moved.expect(200).read("$.data.secrets", Map.class)).isEmpty();
    p.admin().put(MINE + "/EMAIL/" + uid, smtp(PUBLIC_IP, "465", true)).expect(200);
    // Another host: dropped again.
    Response rehosted =
        p.admin().put(MINE + "/EMAIL/" + uid, smtp("93.184.216.35", "465", false)).expect(200);
    assertThat(rehosted.read("$.data.secrets", Map.class)).isEmpty();
    assertThat(rehosted.string("$.data.settings.host")).isEqualTo("93.184.216.35");

    // There is no plain-text switch any more: TLS is always on.
    Response channels = p.admin().get(MINE).expect(200);
    List<String> smtpFields =
        channels.read(
            "$.data[?(@.channel == 'EMAIL')].options[?(@.code == 'smtp')].fields[*].key",
            List.class);
    assertThat(smtpFields).contains("host", "port", "password").doesNotContain("tls");

    // A provider saved before the rules (made internal behind the API's back) cannot be tested:
    // "Send test" follows the same rules, before any connection.
    try (Connection c = tenantConnection(p.tenant())) {
      try (PreparedStatement s =
          c.prepareStatement(
              "update notifications.provider_settings set settings = ?, version = version + 1"
                  + " where uid = ?::uuid")) {
        s.setString(1, "{\"host\":\"10.9.9.9\",\"port\":\"587\",\"from\":\"noreply@x.test\"}");
        s.setString(2, uid);
        assertThat(s.executeUpdate()).isEqualTo(1);
      }
      c.commit();
    }
    Response test =
        p.admin()
            .post(
                MINE + "/EMAIL/test",
                "{\"to\":\"ops@pharmacy.test\",\"providerUid\":\"%s\"}".formatted(uid));
    assertThat(test.expect(422).string("$.reason")).isEqualTo("smtp_host_not_allowed");
  }

  // --------------------------------------------------------------------------- INT-5 SMS cap

  @Test
  @DisplayName("INT-5: over the plan's SMS a month the platform's account sends no more")
  void smsCap() {
    Api root = root();
    logOnly(root, "SMS");
    Pharmacy p = pharmacy(root, "SC");
    String plan = "SMS" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    root.post(
            "/api/v1/platform/plans",
            """
            {"code":"%s","name":"Two SMS","currency":"TZS","priceMonthly":1000,"priceYearly":10000,
             "smsPerMonth":2,"integrations":true}
            """
                .formatted(plan))
        .expect(201);
    root.put(
            "/api/v1/platform/tenants/" + p.tenant() + "/subscription",
            "{\"planCode\":\"%s\",\"cycle\":\"MONTHLY\"}".formatted(plan))
        .expect(200);

    // Two SMS on the platform's account go; the third and fourth do not.
    for (String phone : List.of("+255712000101", "+255712000102")) {
      sms(p.tenant(), phone);
      assertThat(settled(p.admin(), phone).get("status")).isEqualTo("SENT");
    }
    sms(p.tenant(), "+255712000103");
    Map<String, Object> third = settled(p.admin(), "+255712000103");
    assertThat(third.get("status")).isEqualTo("FAILED");
    assertThat(third.get("reason")).isEqualTo("sms_cap_reached");
    assertThat(String.valueOf(third.get("lastError"))).contains("2 SMS a month");
    sms(p.tenant(), "+255712000104");
    assertThat(settled(p.admin(), "+255712000104").get("reason")).isEqualTo("sms_cap_reached");

    // The administrators are told once this month, in their inbox.
    eventually(() -> capNotices(p.admin()) == 1);
    assertThat(capNotices(p.admin())).isEqualTo(1);

    // The subscription's usage counts the SMS on the platform's account, in the pharmacy's month.
    Response usage = p.admin().get("/api/v1/subscription").expect(200);
    assertThat(usage.read("$.data.usage[?(@.limit == 'SMS')].used", List.class)).containsExactly(2);
    // "Send test" through the platform's account is an SMS too.
    Response test = p.admin().post(MINE + "/SMS/test", "{\"to\":\"0712345678\"}");
    assertThat(test.expect(422).string("$.reason")).isEqualTo("sms_cap_reached");

    // The pharmacy's own provider is not capped.
    p.admin().post(MINE + "/SMS", "{\"name\":\"Own\",\"provider\":\"log\"}").expect(201);
    sms(p.tenant(), "+255712000105");
    Map<String, Object> own = settled(p.admin(), "+255712000105");
    assertThat(own.get("status")).isEqualTo("SENT");
    assertThat(own.get("provider")).isEqualTo("log:own:Own");
    assertThat(
            p.admin()
                .get("/api/v1/subscription")
                .expect(200)
                .read("$.data.usage[?(@.limit == 'SMS')].used", List.class))
        .containsExactly(2);
  }

  private void sms(String tenant, String phone) {
    UUID tenantUid = UUID.fromString(tenant);
    ExecutionContext.callAs(
        ExecutionContext.Frame.service("hardening-test", null, tenantUid),
        () ->
            TenantContext.callAs(
                tenantUid,
                () -> {
                  notifier.send(
                      new NotificationRequest(
                          TemplateKeys.SALE_RECEIPT,
                          new Recipient(null, "Customer", null, phone, "en"),
                          EnumSet.of(Channel.SMS),
                          Map.of(
                              "pharmacyName", "Cap Pharmacy",
                              "number", "INV-HQ-000001",
                              "total", "TZS 1,000.00",
                              "date", "today"),
                          null));
                  return null;
                }));
  }

  /** The delivery log row of the SMS to {@code phone}, once it is sent or failed. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> settled(Api admin, String phone) {
    String path = "$.data.items[?(@.to == '" + phone + "')]";
    eventually(
        () -> {
          List<String> status =
              admin
                  .get("/api/v1/notification-deliveries?channel=SMS&limit=100")
                  .expect(200)
                  .read(path + ".status", List.class);
          return status.size() == 1 && List.of("SENT", "FAILED").contains(status.getFirst());
        });
    Response log = admin.get("/api/v1/notification-deliveries?channel=SMS&limit=100").expect(200);
    List<Map<String, Object>> rows = log.read(path, List.class);
    return rows.getFirst();
  }

  private static long capNotices(Api admin) {
    List<String> titles =
        admin
            .get("/api/v1/notifications?limit=100")
            .expect(200)
            .read("$.data.items[*].title", List.class);
    return titles.stream().filter("This month's SMS are used"::equals).count();
  }

  /** Every platform provider of the channel removed, then one log provider. */
  private static void logOnly(Api root, String ch) {
    Response list = root.get(PLATFORM).expect(200);
    List<String> uids =
        list.read("$.data[?(@.channel == '" + ch + "')].providers[*].uid", List.class);
    for (String uid : uids) {
      root.delete(PLATFORM + "/" + ch + "/" + uid).expect(200);
    }
    root.post(PLATFORM + "/" + ch, "{\"provider\":\"log\"}").expect(201);
  }

  // ------------------------------------------------------------------------- INT-13 webhooks

  record Received(Map<String, String> headers, String body) {
    String header(String name) {
      return headers.get(name.toLowerCase());
    }
  }

  /** An HTTP receiver on 127.0.0.2 (the test profile's allowed webhook host). */
  static final class Receiver implements AutoCloseable {
    final List<Received> received = new CopyOnWriteArrayList<>();
    final HttpServer server;
    final long delayMillis;

    Receiver(int port, long delayMillis) throws Exception {
      this.delayMillis = delayMillis;
      server =
          HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), port), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/hook",
          exchange -> {
            Map<String, String> headers = new java.util.HashMap<>();
            exchange
                .getRequestHeaders()
                .forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            if (delayMillis > 0) {
              try {
                Thread.sleep(delayMillis);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            }
            received.add(new Received(headers, body));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.2:" + server.getAddress().getPort() + "/hook";
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /** The Standard Webhooks signature, computed independently of the server's code. */
  static String sign(String secret, String id, String timestamp, String body) throws Exception {
    byte[] key = Base64.getDecoder().decode(secret.substring("whsec_".length()));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return "v1,"
        + Base64.getEncoder()
            .encodeToString(mac.doFinal((id + "." + timestamp + "." + body).getBytes(UTF_8)));
  }

  private static String webhook(Api admin, String url, String types) {
    return admin
        .post(
            "/api/v1/webhooks",
            """
            {"name":"System","url":"%s","eventTypes":[%s],"active":true}
            """
                .formatted(url, types))
        .expect(201)
        .string("$.data.uid");
  }

  private static String product(Api admin, String name) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "sellingPrice":10000,"costPrice":6000}
            """
                .formatted(name, name))
        .expect(201)
        .string("$.data.uid");
  }

  @Test
  @DisplayName("INT-13: secrets sealed, Standard Webhooks signatures, rotation signs with both")
  void webhookSecretsAndSigning() throws Exception {
    Api root = root();
    Pharmacy p = pharmacy(root, "WS");
    try (Receiver receiver = new Receiver(0, 0)) {
      Response created =
          p.admin()
              .post(
                  "/api/v1/webhooks",
                  """
                  {"name":"ERP","url":"%s","eventTypes":["product.changed"],"active":true}
                  """
                      .formatted(receiver.url()))
              .expect(201);
      String hook = created.string("$.data.uid");
      String secret = created.string("$.data.secret");
      assertThat(secret).startsWith("whsec_");
      assertThat(Base64.getDecoder().decode(secret.substring(6))).hasSize(32);
      // Sealed at rest, and never shown again.
      assertThat(storedSecret(p.tenant(), hook))
          .doesNotStartWith("whsec_")
          .doesNotContain(secret.substring(6));
      assertThat(p.admin().get("/api/v1/webhooks").expect(200).body()).doesNotContain(secret);

      product(p.admin(), "Hook one");
      eventually(() -> receiver.received.size() == 1);
      Received first = receiver.received.getFirst();
      String id = first.header("webhook-id");
      String timestamp = first.header("webhook-timestamp");
      assertThat(first.body()).contains("\"id\":\"" + id + "\"");
      assertThat(Math.abs(Long.parseLong(timestamp) - Instant.now().getEpochSecond()))
          .isLessThan(300);
      assertThat(first.header("webhook-signature"))
          .isEqualTo(sign(secret, id, timestamp, first.body()));
      // The old header, kept for one release.
      assertThat(first.header("X-Pharmacy-Signature")).startsWith("sha256=");

      // Rotation: the new secret once; for a day deliveries carry both signatures.
      Response rotated =
          p.admin().post("/api/v1/webhooks/" + hook + "/rotate-secret", "{}").expect(200);
      String fresh = rotated.string("$.data.secret");
      assertThat(fresh).startsWith("whsec_").isNotEqualTo(secret);
      assertThat(Instant.parse(rotated.string("$.data.previousSecretUntil")))
          .isAfter(Instant.now().plusSeconds(23 * 3600));
      assertThat(p.admin().get("/api/v1/webhooks").expect(200).body()).doesNotContain(fresh);
      product(p.admin(), "Hook two");
      eventually(() -> receiver.received.size() == 2);
      Received second = receiver.received.get(1);
      String id2 = second.header("webhook-id");
      String ts2 = second.header("webhook-timestamp");
      assertThat(second.header("webhook-signature").split(" "))
          .containsExactly(
              sign(fresh, id2, ts2, second.body()), sign(secret, id2, ts2, second.body()));

      // A secret saved before sealing (plain whsec_<hex>) still signs, and is sealed on use.
      byte[] raw = new byte[32];
      new java.security.SecureRandom().nextBytes(raw);
      String legacy = "whsec_" + HexFormat.of().formatHex(raw);
      try (Connection c = tenantConnection(p.tenant())) {
        try (PreparedStatement s =
            c.prepareStatement(
                "update integrations.webhooks set secret = ?, previous_secret = null,"
                    + " previous_until = null where uid = ?::uuid")) {
          s.setString(1, legacy);
          s.setString(2, hook);
          assertThat(s.executeUpdate()).isEqualTo(1);
        }
        c.commit();
      }
      product(p.admin(), "Hook three");
      eventually(() -> receiver.received.size() == 3);
      Received third = receiver.received.get(2);
      assertThat(third.header("webhook-signature"))
          .isEqualTo(
              sign(
                  legacy,
                  third.header("webhook-id"),
                  third.header("webhook-timestamp"),
                  third.body()));
      eventually(() -> !storedSecret(p.tenant(), hook).startsWith("whsec_"));
    }
  }

  @Test
  @DisplayName("INT-13: at most 10 webhooks per pharmacy")
  void webhookLimit() {
    Pharmacy p = pharmacy(root(), "WL");
    for (int i = 0; i < Webhooks.MAX_WEBHOOKS; i++) {
      webhook(p.admin(), "http://127.0.0.2:9/hook" + i, "\"stock.low\"");
    }
    Response eleventh =
        p.admin()
            .post(
                "/api/v1/webhooks",
                "{\"name\":\"One more\",\"url\":\"http://127.0.0.2:9/x\","
                    + "\"eventTypes\":[\"stock.low\"]}");
    assertThat(eleventh.expect(422).string("$.reason")).isEqualTo("webhook_limit");
  }

  @Test
  @DisplayName("INT-13: a hung receiver does not hold back another webhook's delivery")
  void dispatchSideBySide() throws Exception {
    Pharmacy p = pharmacy(root(), "WP");
    InetAddress host = InetAddress.getByName("127.0.0.2");
    List<Socket> held = new CopyOnWriteArrayList<>();
    try (ServerSocket hung = new ServerSocket(0, 50, host);
        Receiver healthy = new Receiver(0, 0)) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  while (!hung.isClosed()) {
                    held.add(hung.accept());
                  }
                } catch (java.io.IOException closed) {
                  // The test is over.
                }
              });
      // The hung one first: one after the other, the healthy one would wait about 18 s.
      webhook(
          p.admin(), "http://127.0.0.2:" + hung.getLocalPort() + "/hook", "\"product.changed\"");
      webhook(p.admin(), healthy.url(), "\"product.changed\"");
      long started = System.nanoTime();
      product(p.admin(), "Side by side");
      eventually(() -> healthy.received.size() == 1);
      long seconds = (System.nanoTime() - started) / 1_000_000_000L;
      assertThat(seconds).as("the healthy receiver was not held back").isLessThan(8);
    } finally {
      for (Socket s : held) {
        s.close();
      }
    }
  }

  @Test
  @DisplayName("INT-13: two retry runs at once send a due delivery once (claimed, SKIP LOCKED)")
  void retriesClaimDeliveries() throws Exception {
    Pharmacy p = pharmacy(root(), "WC");
    InetAddress host = InetAddress.getByName("127.0.0.2");
    int port;
    try (ServerSocket probe = new ServerSocket(0, 1, host)) {
      port = probe.getLocalPort();
    }
    String hook = webhook(p.admin(), "http://127.0.0.2:" + port + "/hook", "\"product.changed\"");
    product(p.admin(), "Retried once");
    String deliveries = "/api/v1/webhooks/" + hook + "/deliveries";
    eventually(
        () ->
            p.admin()
                .get(deliveries)
                .expect(200)
                .read("$.data.items[*].status", List.class)
                .equals(List.of("FAILED")));
    try (Receiver slow = new Receiver(port, 1500)) {
      dueNow(p.tenant());
      UUID tenant = UUID.fromString(p.tenant());
      List<Future<Integer>> runs = new ArrayList<>();
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < 2; i++) {
          runs.add(
              pool.submit(
                  () ->
                      ExecutionContext.callAs(
                          ExecutionContext.Frame.service("webhook-retries", null, tenant),
                          () -> TenantContext.callAs(tenant, webhooks::retryDue))));
        }
      }
      eventually(() -> slow.received.size() == 1);
      Thread.sleep(3000);
      assertThat(slow.received).hasSize(1);
      eventually(
          () ->
              p.admin()
                  .get(deliveries)
                  .expect(200)
                  .read("$.data.items[*].status", List.class)
                  .equals(List.of("DELIVERED")));
      assertThat(runs.stream().map(IntegrationsHardeningTest::join).mapToInt(i -> i).sum())
          .isLessThanOrEqualTo(1);
    }
  }

  private static int join(Future<Integer> run) {
    try {
      return Objects.requireNonNullElse(run.get(), 0);
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    }
  }

  // ------------------------------------------------------------------------- INT-15 payments

  @Test
  @DisplayName("INT-15: a payment request pays only a tender of its method at its branch")
  void paymentMismatch() {
    Api root = root();
    Pharmacy p = pharmacy(root, "PM");
    root.put(
            "/api/v1/platform/payment-methods",
            """
            {"code":"SIMULATOR","name":"Test push payment (simulator)","kind":"MOBILE_MONEY",
             "adapter":"SIMULATOR","requiresReference":true,"enabled":true,"sortOrder":90}
            """)
        .expect(200);
    p.admin()
        .put("/api/v1/payment-methods", "{\"methodCode\":\"SIMULATOR\",\"enabled\":true}")
        .expect(200);
    String product = product(p.admin(), "Mismatch tablets");
    String second =
        p.admin()
            .post("/api/v1/branches", "{\"code\":\"B2\",\"name\":\"Second\"}")
            .expect(201)
            .string("$.data.uid");
    Api secondTill = p.admin().in(p.tenant(), second);
    for (Api till : List.of(p.till(), secondTill)) {
      till.post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"M-1","expiryDate":"%s","quantity":10,
               "unitCost":6000}
              """
                  .formatted(product, LocalDate.now().plusYears(1)))
          .expect(201);
    }
    String intent =
        p.till()
            .post(
                "/api/v1/pos/payment-requests",
                """
                {"methodCode":"SIMULATOR","amount":10000,"currency":"TZS","phone":"+255754000111"}
                """)
            .expect(201)
            .string("$.data.uid");
    p.till().get("/api/v1/pos/payment-requests/" + intent).expect(200);

    // Another method's tender cannot use it...
    assertThat(sell(p.till(), product, "MPESA", intent).expect(422).string("$.reason"))
        .isEqualTo("payment_mismatch");
    // ...nor another branch's sale.
    assertThat(sell(secondTill, product, "SIMULATOR", intent).expect(422).string("$.reason"))
        .isEqualTo("payment_mismatch");
    // Its own method at its own branch pays.
    sell(p.till(), product, "SIMULATOR", intent).expect(201);
  }

  private static Response sell(Api till, String product, String channel, String reference) {
    return till.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
         "payments":[{"method":"MOBILE_MONEY","amount":10000,"channel":"%s","reference":"%s"}]}
        """
            .formatted(UUID.randomUUID(), product, channel, reference));
  }

  // -------------------------------------------------------------------------------- helpers

  /** A connection as the application role, in the pharmacy's scope; the caller commits. */
  private Connection tenantConnection(String tenantUid) throws SQLException {
    Connection c = dataSource.getConnection();
    c.setAutoCommit(false);
    try (PreparedStatement s =
        c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
      s.setString(1, tenantUid);
      s.execute();
    }
    return c;
  }

  private String storedSecret(String tenantUid, String webhookUid) {
    try (Connection c = tenantConnection(tenantUid);
        PreparedStatement s =
            c.prepareStatement("select secret from integrations.webhooks where uid = ?::uuid")) {
      s.setString(1, webhookUid);
      try (ResultSet r = s.executeQuery()) {
        assertThat(r.next()).isTrue();
        String secret = r.getString(1);
        c.commit();
        return secret;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** Brings the pharmacy's scheduled webhook retries forward to now. */
  private void dueNow(String tenantUid) throws SQLException {
    try (Connection c = tenantConnection(tenantUid);
        PreparedStatement s =
            c.prepareStatement(
                "update integrations.deliveries set next_attempt_at = now() - interval '1 second'"
                    + " where status = 'FAILED' and next_attempt_at is not null")) {
      s.executeUpdate();
      c.commit();
    }
  }
}
