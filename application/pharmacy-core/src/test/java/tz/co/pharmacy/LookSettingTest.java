package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * A pharmacy chooses how the web app looks for everyone in it: PREMIUM (the modern look) or CLASSIC
 * (Windows 2000 style). Every member reads it with the settings; only an admin changes it.
 */
class LookSettingTest extends IntegrationTest {

  private Api admin;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "LK" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Look %s","legalName":"Look %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
  }

  private String look() {
    return admin.get("/api/v1/settings").expect(200).string("$.data.look");
  }

  @Test
  @DisplayName("a new pharmacy starts with the premium look")
  void startsPremium() {
    assertThat(look()).isEqualTo("PREMIUM");
  }

  @Test
  @DisplayName("an admin switches the whole pharmacy to the classic look and back")
  void switchesLook() {
    assertThat(
            admin
                .put("/api/v1/settings", "{\"look\":\"classic\"}")
                .expect(200)
                .string("$.data.look"))
        .isEqualTo("CLASSIC");
    assertThat(look()).isEqualTo("CLASSIC");

    // Saving other settings keeps the look.
    admin.put("/api/v1/settings", "{\"receiptFooter\":\"Asante\"}").expect(200);
    assertThat(look()).isEqualTo("CLASSIC");

    admin.put("/api/v1/settings", "{\"look\":\"PREMIUM\"}").expect(200);
    assertThat(look()).isEqualTo("PREMIUM");
  }

  @Test
  @DisplayName("an unknown look is refused")
  void unknownLook() {
    assertThat(admin.put("/api/v1/settings", "{\"look\":\"NEON\"}").expect(422).string("$.reason"))
        .isEqualTo("invalid_look");
    assertThat(look()).isEqualTo("PREMIUM");
  }
}
