package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Message providers kept in the database: several per channel for the platform and for a pharmacy,
 * tried in order (failover); secrets sealed and never shown; test sends; the monthly usage count.
 * No test reaches a real network: the failing provider is SMTP on a closed local port.
 */
class NotificationProvidersTest extends IntegrationTest {

  private static final String PLATFORM = "/api/v1/platform/notification-providers";
  private static final String MINE = "/api/v1/notification-providers";

  @Autowired DataSource dataSource;

  private static String channel(Response list, String channel, String path) {
    return "$.data[?(@.channel == '" + channel + "')]" + path;
  }

  /** Every platform provider of the channel removed, then one log provider: the test baseline. */
  private static void logOnly(Api root, String ch) {
    Response list = root.get(PLATFORM).expect(200);
    List<String> uids = list.read(channel(list, ch, ".providers[*].uid"), List.class);
    for (String uid : uids) {
      root.delete(PLATFORM + "/" + ch + "/" + uid).expect(200);
    }
    root.post(PLATFORM + "/" + ch, "{\"provider\":\"log\"}").expect(201);
  }

  @Test
  @DisplayName("platform list, pharmacy's own list, masked secrets, test sends and usage")
  void providers() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));

    // A Beem account added to the platform's SMS list: secrets come back masked, never in clear.
    Response beem =
        root.post(
                PLATFORM + "/SMS",
                """
                {"name":"Beem main","provider":"beem","settings":{"senderId":"PHARMA"},
                 "secrets":{"apiKey":"beem-key-123456","secretKey":"beem-secret-987654"}}
                """)
            .expect(201);
    String beemUid = beem.string("$.data.uid");
    assertThat(beem.string("$.data.secrets.apiKey")).isEqualTo("••••3456");
    assertThat(beem.read("$.data.configured", Boolean.class)).isTrue();
    assertThat(beem.string("$.data.name")).isEqualTo("Beem main");
    String listed = root.get(PLATFORM).expect(200).body();
    assertThat(listed).doesNotContain("beem-key-123456").doesNotContain("beem-secret-987654");

    // A change that names no secret keeps the stored ones; a missing required field is refused.
    Response renamed =
        root.put(
                PLATFORM + "/SMS/" + beemUid,
                "{\"provider\":\"beem\",\"settings\":{\"senderId\":\"PHARMACY\"}}")
            .expect(200);
    assertThat(renamed.string("$.data.secrets.secretKey")).isEqualTo("••••7654");
    Response incomplete =
        root.post(
            PLATFORM + "/SMS",
            "{\"provider\":\"africastalking\",\"settings\":{\"username\":\"pharma\"}}");
    assertThat(incomplete.expect(422).string("$.reason")).isEqualTo("provider_incomplete");

    // Log providers only, so no test reaches a real network; the platform's rows now route.
    logOnly(root, "SMS");
    logOnly(root, "EMAIL");
    Response platformTest =
        root.post(PLATFORM + "/EMAIL/test", "{\"to\":\"ops@pharmacy.test\"}").expect(200);
    assertThat(platformTest.read("$.data.ok", Boolean.class)).isTrue();
    assertThat(platformTest.string("$.data.source")).isEqualTo("PLATFORM");

    // A new pharmacy: its welcome email goes through the platform's provider.
    String code = "NP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Notify %s","legalName":"Notify %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    Api.eventually(
        () ->
            admin
                .get("/api/v1/notification-deliveries?channel=EMAIL")
                .expect(200)
                .body()
                .contains("\"log:platform\""));

    Response mine = admin.get(MINE).expect(200);
    assertThat(mine.read(channel(mine, "SMS", ".mode"), List.class)).containsExactly("PLATFORM");
    assertThat(mine.read(channel(mine, "SMS", ".effectiveSource"), List.class))
        .containsExactly("PLATFORM");

    // The pharmacy adds its own SMS provider: its messages now go through it.
    String own =
        admin
            .post(MINE + "/SMS", "{\"name\":\"Our SMS\",\"provider\":\"log\"}")
            .expect(201)
            .string("$.data.uid");
    Response test = admin.post(MINE + "/SMS/test", "{\"to\":\"0712345678\"}").expect(200);
    assertThat(test.read("$.data.ok", Boolean.class)).isTrue();
    assertThat(test.string("$.data.source")).isEqualTo("OWN");
    assertThat(test.string("$.data.provider")).isEqualTo("log:own:Our SMS");
    Response log = admin.get("/api/v1/notification-deliveries?channel=SMS").expect(200);
    assertThat(log.read("$.data.items[0].provider", String.class)).isEqualTo("log:own:Our SMS");
    assertThat(log.read("$.data.items[0].to", String.class)).isEqualTo("+255712345678");
    Response bad = admin.post(MINE + "/SMS/test", "{\"to\":\"not a phone\"}");
    assertThat(bad.expect(422).string("$.reason")).isEqualTo("invalid_phone");

    // Switched off, the pharmacy is back on the platform's list; its own is kept.
    admin
        .put(MINE + "/SMS/" + own, "{\"name\":\"Our SMS\",\"provider\":\"log\",\"active\":false}")
        .expect(200);
    Response back = admin.get(MINE).expect(200);
    assertThat(back.read(channel(back, "SMS", ".effectiveSource"), List.class))
        .containsExactly("PLATFORM");
    assertThat(back.read(channel(back, "SMS", ".providers.length()"), List.class))
        .containsExactly(1);

    // Usage counts the sent SMS test this month.
    Response usage = admin.get("/api/v1/notification-usage").expect(200);
    assertThat(usage.read("$.data.SMS", Integer.class)).isGreaterThanOrEqualTo(1);
    assertThat(usage.read("$.data.EMAIL", Integer.class)).isGreaterThanOrEqualTo(1);

    // A pharmacy cannot reach the platform's settings, nor touch a platform provider by its uid.
    admin.get(PLATFORM).expect(403);
    Response platformList = root.get(PLATFORM).expect(200);
    List<String> platformSms =
        platformList.read(channel(platformList, "SMS", ".providers[*].uid"), List.class);
    admin.put(MINE + "/SMS/" + platformSms.getFirst(), "{\"provider\":\"log\"}").expect(404);
  }

  @Test
  @DisplayName("several providers fail over in order; a failing one trips and goes last")
  void failover() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    logOnly(root, "EMAIL");
    Response list = root.get(PLATFORM).expect(200);
    List<String> emailUids = list.read(channel(list, "EMAIL", ".providers[*].uid"), List.class);
    String backup = emailUids.getFirst();
    root.put(PLATFORM + "/EMAIL/" + backup, "{\"name\":\"Backup\",\"provider\":\"log\"}")
        .expect(200);
    // A broken SMTP server (a closed local port), put first.
    String broken =
        root.post(
                PLATFORM + "/EMAIL",
                """
                {"name":"Broken SMTP","provider":"smtp",
                 "settings":{"host":"127.0.0.1","port":"1","from":"noreply@pharmacy.test"}}
                """)
            .expect(201)
            .string("$.data.uid");
    Response ordered =
        root.put(PLATFORM + "/EMAIL/order", "{\"order\":[\"%s\",\"%s\"]}".formatted(broken, backup))
            .expect(200);
    assertThat(ordered.read("$.data.chain", List.class))
        .containsExactly("smtp:platform:Broken SMTP", "log:platform:Backup");
    root.put(PLATFORM + "/EMAIL/order", "{\"order\":[\"%s\"]}".formatted(broken)).expect(422);

    // The channel test goes as messages go: the broken one fails, the backup sends.
    Response test =
        root.post(PLATFORM + "/EMAIL/test", "{\"to\":\"ops@pharmacy.test\"}").expect(200);
    assertThat(test.read("$.data.ok", Boolean.class)).isTrue();
    assertThat(test.string("$.data.provider")).isEqualTo("log:platform:Backup");
    // Testing the broken one alone reports its failure.
    Response alone =
        root.post(
                PLATFORM + "/EMAIL/test",
                "{\"to\":\"ops@pharmacy.test\",\"providerUid\":\"%s\"}".formatted(broken))
            .expect(200);
    assertThat(alone.read("$.data.ok", Boolean.class)).isFalse();

    // Real messages (three new pharmacies' welcome emails) fail over to the backup...
    for (int i = 0; i < 3; i++) {
      String code = "FO" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
      String email = code.toLowerCase() + "@test.local";
      String tenant =
          root.post(
                  "/api/v1/platform/tenants",
                  """
                  {"code":"%s","name":"Failover %s","legalName":"Failover %s Ltd","country":"TZ",
                   "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                   "headOffice":{"code":"HQ","name":"Head Office"},
                   "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                  """
                      .formatted(code, code, code, email))
              .expect(201)
              .string("$.data.uid");
      Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
      Api.eventually(
          () ->
              admin
                  .get("/api/v1/notification-deliveries?channel=EMAIL")
                  .expect(200)
                  .body()
                  .contains("\"log:platform:Backup\""));
    }
    // ...and the broken one, failing each time, has tripped: it is tried last now.
    Api.eventually(
        () -> {
          Response after = root.get(PLATFORM).expect(200);
          List<Boolean> tripped =
              after.read(
                  channel(after, "EMAIL", ".providers[?(@.name == 'Broken SMTP')].tripped"),
                  List.class);
          return tripped.equals(List.of(true));
        });
    Response after = root.get(PLATFORM).expect(200);
    assertThat(after.read(channel(after, "EMAIL", ".chain"), List.class).toString())
        .startsWith("[[\"log:platform:Backup\"");
    assertThat(
            after.read(
                channel(after, "EMAIL", ".providers[?(@.name == 'Broken SMTP')].lastError"),
                List.class))
        .isNotEmpty();

    logOnly(root, "EMAIL");
  }

  @Test
  @DisplayName("a provider that hangs is given up on after its time limit, and the backup sends")
  void hangingProvider() throws Exception {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    logOnly(root, "EMAIL");
    // A mail server that takes the connection and never says a word.
    try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
      List<java.net.Socket> held = new java.util.concurrent.CopyOnWriteArrayList<>();
      Thread acceptor =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      while (!silent.isClosed()) {
                        held.add(silent.accept());
                      }
                    } catch (java.io.IOException closed) {
                      // The test is over.
                    }
                  });
      Response list = root.get(PLATFORM).expect(200);
      String backup =
          list.read(channel(list, "EMAIL", ".providers[0].uid"), List.class).getFirst().toString();
      String hung =
          root.post(
                  PLATFORM + "/EMAIL",
                  """
                  {"name":"Hung SMTP","provider":"smtp",
                   "settings":{"host":"127.0.0.1","port":"%d","from":"noreply@pharmacy.test"}}
                  """
                      .formatted(silent.getLocalPort()))
              .expect(201)
              .string("$.data.uid");
      root.put(PLATFORM + "/EMAIL/order", "{\"order\":[\"%s\",\"%s\"]}".formatted(hung, backup))
          .expect(200);

      long started = System.nanoTime();
      Response test =
          root.post(PLATFORM + "/EMAIL/test", "{\"to\":\"ops@pharmacy.test\"}").expect(200);
      long seconds = (System.nanoTime() - started) / 1_000_000_000L;

      assertThat(test.read("$.data.ok", Boolean.class)).isTrue();
      assertThat(test.string("$.data.provider")).startsWith("log:platform");
      assertThat(seconds).as("given up on within its 10 s limit").isBetween(5L, 25L);
      assertThat(held).isNotEmpty();
      // Tried alone, the hung one reports why.
      Response alone =
          root.post(
                  PLATFORM + "/EMAIL/test",
                  "{\"to\":\"ops@pharmacy.test\",\"providerUid\":\"%s\"}".formatted(hung))
              .expect(200);
      assertThat(alone.read("$.data.ok", Boolean.class)).isFalse();
      assertThat(alone.string("$.data.error")).contains("did not answer in time");
      for (java.net.Socket s : held) {
        s.close();
      }
      silent.close();
      acceptor.join(2000);
    }
    logOnly(root, "EMAIL");
  }

  @Test
  @DisplayName("secrets sealed under another key: the page loads, sends explain, a change recovers")
  void unreadableSecrets() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String twilio =
        root.post(
                PLATFORM + "/WHATSAPP",
                """
                {"provider":"twilio","settings":{"accountSid":"AC123","from":"+14155238886"},
                 "secrets":{"authToken":"twilio-token-123456"}}
                """)
            .expect(201)
            .string("$.data.uid");

    // As if the server's encryption key had changed since the save.
    new JdbcTemplate(dataSource)
        .update(
            "update notifications.provider_settings set secrets = ? where uid = ?::uuid",
            Base64.getEncoder().encodeToString(new byte[48]),
            twilio);

    Response listed = root.get(PLATFORM).expect(200);
    String it = "$.data[?(@.channel == 'WHATSAPP')].providers[?(@.uid == '" + twilio + "')]";
    assertThat(listed.read(it + ".secretsUnreadable", List.class)).containsExactly(true);
    assertThat(listed.read(it + ".configured", List.class)).containsExactly(false);
    assertThat(listed.read(it + ".secrets", List.class)).containsExactly(Map.of());

    // A send says what to do instead of failing obscurely (and reaches no network).
    Response test =
        root.post(
                PLATFORM + "/WHATSAPP/test",
                "{\"to\":\"+255712345678\",\"providerUid\":\"%s\"}".formatted(twilio))
            .expect(200);
    assertThat(test.read("$.data.ok", Boolean.class)).isFalse();
    assertThat(test.string("$.data.error")).contains("can no longer be read");

    // A change without the secret asks for it; with it, the provider is whole again.
    Response missing =
        root.put(
            PLATFORM + "/WHATSAPP/" + twilio,
            "{\"provider\":\"twilio\",\"settings\":{\"accountSid\":\"AC123\","
                + "\"from\":\"+14155238886\"}}");
    assertThat(missing.expect(422).string("$.field")).isEqualTo("secrets.authToken");
    Response saved =
        root.put(
                PLATFORM + "/WHATSAPP/" + twilio,
                """
                {"provider":"twilio","settings":{"accountSid":"AC123","from":"+14155238886"},
                 "secrets":{"authToken":"twilio-token-654321"}}
                """)
            .expect(200);
    assertThat(saved.read("$.data.secretsUnreadable", Boolean.class)).isFalse();
    assertThat(saved.read("$.data.configured", Boolean.class)).isTrue();
    assertThat(saved.string("$.data.secrets.authToken")).isEqualTo("••••4321");

    logOnly(root, "WHATSAPP");
  }
}
