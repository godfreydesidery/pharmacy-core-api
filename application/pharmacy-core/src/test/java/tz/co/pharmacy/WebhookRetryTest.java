package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * A pharmacy's own system is down when a sale happens: the webhook delivery fails, is scheduled
 * again, and reaches the system once it is back (the chaos run found such events lost for good).
 */
class WebhookRetryTest extends IntegrationTest {

  @Autowired DataSource dataSource;

  @Test
  @DisplayName("a delivery missed while the receiver was down reaches it once it is back")
  void retriedUntilDelivered() throws Exception {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "WR" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Hooks %s","legalName":"Hooks %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    Api till = admin.in(tenant, branch);

    // A port nothing listens on yet: the receiver is down.
    InetAddress host = InetAddress.getByName("127.0.0.2");
    int port;
    try (ServerSocket probe = new ServerSocket(0, 1, host)) {
      port = probe.getLocalPort();
    }
    String hook =
        admin
            .post(
                "/api/v1/webhooks",
                """
                {"name":"Back office","url":"http://127.0.0.2:%d/hook",
                 "eventTypes":["sale.completed"],"active":true}
                """
                    .formatted(port))
            .expect(201)
            .string("$.data.uid");

    String product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"ORS Sachet","genericName":"Oral rehydration salts","dosageForm":"POWDER",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                 "sellingPrice":800,"costPrice":300,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":5,"unitCost":300}
            """
                .formatted(product, LocalDate.now().plusYears(2)))
        .expect(201);
    till.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
             "payments":[{"method":"CASH","amount":800}]}
            """
                .formatted(UUID.randomUUID(), product))
        .expect(201);

    String deliveries = "/api/v1/webhooks/" + hook + "/deliveries";
    eventually(
        () -> {
          List<String> status =
              admin.get(deliveries).expect(200).json().read("$.data.items[*].status");
          return status.equals(List.of("FAILED"));
        });
    Api.Response failed = admin.get(deliveries).expect(200);
    assertThat(failed.string("$.data.items[0].nextAttemptAt")).isNotNull();

    // The receiver comes back; the next attempt is made due now.
    List<String> received = new CopyOnWriteArrayList<>();
    HttpServer receiver = HttpServer.create(new InetSocketAddress(host, port), 0);
    receiver.createContext(
        "/hook",
        exchange -> {
          received.add(new String(exchange.getRequestBody().readAllBytes()));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    receiver.start();
    try {
      dueNow(tenant);
      eventually(() -> received.size() == 1);
      eventually(
          () -> {
            List<String> status =
                admin.get(deliveries).expect(200).json().read("$.data.items[*].status");
            return status.equals(List.of("DELIVERED"));
          });
      Api.Response delivered = admin.get(deliveries).expect(200);
      assertThat(delivered.read("$.data.items[0].attempts", Integer.class)).isGreaterThan(3);
      assertThat(delivered.string("$.data.items[0].nextAttemptAt")).isNull();
      assertThat(received.getFirst()).contains("\"type\":\"sale.completed\"");
    } finally {
      receiver.stop(0);
    }
  }

  @Test
  @DisplayName("a hung system does not hold back the retries of a healthy one")
  void hungSystemDoesNotBlockAnother() throws Exception {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "WB" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Lanes %s","legalName":"Lanes %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    Api till = admin.in(tenant, branch);
    InetAddress host = InetAddress.getByName("127.0.0.2");
    // A system that takes the connection and never answers.
    List<java.net.Socket> held = new CopyOnWriteArrayList<>();
    try (ServerSocket hung = new ServerSocket(0, 50, host)) {
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
      int backPort;
      try (ServerSocket probe = new ServerSocket(0, 1, host)) {
        backPort = probe.getLocalPort();
      }
      for (String url :
          List.of(
              "http://127.0.0.2:" + hung.getLocalPort() + "/hook",
              "http://127.0.0.2:" + backPort + "/hook")) {
        admin
            .post(
                "/api/v1/webhooks",
                """
                {"name":"System","url":"%s","eventTypes":["sale.completed"],"active":true}
                """
                    .formatted(url))
            .expect(201);
      }
      String product =
          admin
              .post(
                  "/api/v1/products",
                  """
                  {"name":"ORS Sachet","genericName":"Oral rehydration salts","dosageForm":"POWDER",
                   "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                   "sellingPrice":800,"costPrice":300,"taxRatePercent":0}
                  """)
              .expect(201)
              .string("$.data.uid");
      till.post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":10,"unitCost":300}
              """
                  .formatted(product, LocalDate.now().plusYears(2)))
          .expect(201);
      for (int i = 0; i < 4; i++) {
        till.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
                 "payments":[{"method":"CASH","amount":800}]}
                """
                    .formatted(UUID.randomUUID(), product))
            .expect(201);
      }
      // Every first round over (the hung one takes about 18 s a delivery).
      Thread.sleep(25_000);
      List<String> received = new CopyOnWriteArrayList<>();
      HttpServer receiver = HttpServer.create(new InetSocketAddress(host, backPort), 0);
      receiver.createContext(
          "/hook",
          exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      receiver.start();
      try {
        long started = System.nanoTime();
        dueNow(tenant);
        eventually(() -> received.size() == 4);
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        // Sequentially behind four hung deliveries it would take over a minute.
        assertThat(seconds).as("the healthy system was not held back").isLessThan(15);
      } finally {
        receiver.stop(0);
        for (java.net.Socket s : held) {
          s.close();
        }
      }
    }
  }

  /** Brings the pharmacy's scheduled retries forward to now (instead of waiting a minute). */
  private void dueNow(String tenantUid) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement s =
          c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        s.setString(1, tenantUid);
        s.execute();
      }
      try (PreparedStatement s =
          c.prepareStatement(
              "update integrations.deliveries set next_attempt_at = now() - interval '1 second'"
                  + " where status = 'FAILED' and next_attempt_at is not null")) {
        s.executeUpdate();
      }
      c.commit();
    }
  }
}
