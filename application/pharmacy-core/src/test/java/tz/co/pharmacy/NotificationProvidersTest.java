package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Message providers kept in the database: the platform's default and a pharmacy's own account,
 * resolved per send; secrets sealed and never shown; test sends; the monthly usage count.
 */
class NotificationProvidersTest extends IntegrationTest {

  @Test
  @DisplayName("platform default, pharmacy override, masked secrets, test send and usage")
  void providers() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));

    // Out of the box every channel uses the environment's provider (log in tests).
    Response initial = root.get("/api/v1/platform/notification-providers").expect(200);
    assertThat(initial.read("$.data[?(@.channel == 'SMS')].effectiveSource", List.class))
        .containsExactly("ENVIRONMENT");

    // A Beem account for the platform: secrets come back masked, never in clear.
    Response beem =
        root.put(
                "/api/v1/platform/notification-providers/SMS",
                """
                {"provider":"beem","settings":{"senderId":"PHARMA"},
                 "secrets":{"apiKey":"beem-key-123456","secretKey":"beem-secret-987654"}}
                """)
            .expect(200);
    assertThat(beem.string("$.data.secrets.apiKey")).isEqualTo("••••3456");
    assertThat(beem.read("$.data.configured", Boolean.class)).isTrue();
    assertThat(beem.string("$.data.effectiveSource")).isEqualTo("PLATFORM");
    String listed = root.get("/api/v1/platform/notification-providers").expect(200).body();
    assertThat(listed).doesNotContain("beem-key-123456").doesNotContain("beem-secret-987654");

    // A save that names no secret keeps the stored ones; a missing required field is refused.
    Response renamed =
        root.put(
                "/api/v1/platform/notification-providers/SMS",
                "{\"provider\":\"beem\",\"settings\":{\"senderId\":\"PHARMACY\"}}")
            .expect(200);
    assertThat(renamed.string("$.data.secrets.secretKey")).isEqualTo("••••7654");
    Response incomplete =
        root.put(
            "/api/v1/platform/notification-providers/SMS",
            "{\"provider\":\"africastalking\",\"settings\":{\"username\":\"pharma\"}}");
    assertThat(incomplete.expect(422).string("$.reason")).isEqualTo("provider_incomplete");

    // Back to log providers so no test reaches a real network; the platform's rows now route.
    root.put("/api/v1/platform/notification-providers/SMS", "{\"provider\":\"log\"}").expect(200);
    root.put("/api/v1/platform/notification-providers/EMAIL", "{\"provider\":\"log\"}").expect(200);
    Response platformTest =
        root.post(
                "/api/v1/platform/notification-providers/EMAIL/test",
                "{\"to\":\"ops@pharmacy.test\"}")
            .expect(200);
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
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    Api.eventually(
        () ->
            admin
                .get("/api/v1/notification-deliveries?channel=EMAIL")
                .expect(200)
                .body()
                .contains("\"log:platform\""));

    Response mine = admin.get("/api/v1/notification-providers").expect(200);
    assertThat(mine.read("$.data[?(@.channel == 'SMS')].effectiveSource", List.class))
        .containsExactly("PLATFORM");
    assertThat(mine.read("$.data[?(@.channel == 'SMS')].mode", List.class))
        .containsExactly("PLATFORM");

    // The pharmacy connects its own SMS account: its messages now go through it.
    Response own =
        admin
            .put("/api/v1/notification-providers/SMS", "{\"mode\":\"OWN\",\"provider\":\"log\"}")
            .expect(200);
    assertThat(own.string("$.data.effectiveSource")).isEqualTo("OWN");
    Response test =
        admin
            .post("/api/v1/notification-providers/SMS/test", "{\"to\":\"0712345678\"}")
            .expect(200);
    assertThat(test.read("$.data.ok", Boolean.class)).isTrue();
    assertThat(test.string("$.data.source")).isEqualTo("OWN");
    Response log = admin.get("/api/v1/notification-deliveries?channel=SMS").expect(200);
    assertThat(log.read("$.data.items[0].provider", String.class)).isEqualTo("log:own");
    assertThat(log.read("$.data.items[0].to", String.class)).isEqualTo("+255712345678");
    Response bad =
        admin.post("/api/v1/notification-providers/SMS/test", "{\"to\":\"not a phone\"}");
    assertThat(bad.expect(422).string("$.reason")).isEqualTo("invalid_phone");

    // Back to the platform's: the own account is kept but no longer used.
    admin.put("/api/v1/notification-providers/SMS", "{\"mode\":\"PLATFORM\"}").expect(200);
    Response back = admin.get("/api/v1/notification-providers").expect(200);
    assertThat(back.read("$.data[?(@.channel == 'SMS')].effectiveSource", List.class))
        .containsExactly("PLATFORM");

    // Usage counts the sent SMS test this month.
    Response usage = admin.get("/api/v1/notification-usage").expect(200);
    assertThat(usage.read("$.data.SMS", Integer.class)).isGreaterThanOrEqualTo(1);
    assertThat(usage.read("$.data.EMAIL", Integer.class)).isGreaterThanOrEqualTo(1);

    // A pharmacy cannot reach the platform's settings.
    admin.get("/api/v1/platform/notification-providers").expect(403);
  }
}
