package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Outbox;
import tz.co.pharmacy.support.Staff;

/**
 * The person's own account security (gaps G3, G5, G9, G13): email change confirmed from the new
 * mailbox, phone verification, reset by phone, assisted recovery with a hold, and the security
 * history. Pharmacy staff logins have none of it.
 */
class AccountSecurityTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api anonymous;
  private Api root;
  private Outbox outbox;
  private String tenant;
  private String email;
  private Api person;

  @BeforeEach
  void person() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    outbox = new Outbox(url, owner, ownerPassword);
    String code = "AS" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    email = "as." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Secure %s","legalName":"Secure %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Amina Hassan","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    person = anonymous.as(anonymous.signIn(email, "Admin#2026x"));
  }

  private static String newPhone() {
    return "+2557" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
  }

  private String smsCode(String phone) {
    return Outbox.code(outbox.platformMessage("access.verification_code", "SMS", phone, true));
  }

  private List<String> history(Api who) {
    return who.get("/api/v1/auth/security/history?limit=50")
        .expect(200)
        .json()
        .read("$.data.items[*].action");
  }

  @Test
  @DisplayName(
      "an email changes only from the new mailbox, with the password, and the old one is told")
  void emailChange() {
    String next = "next." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    assertThat(
            person
                .post(
                    "/api/v1/auth/email-change",
                    "{\"newEmail\":\"%s\",\"password\":\"wrong-one\"}".formatted(next))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_password");
    person
        .post(
            "/api/v1/auth/email-change",
            "{\"newEmail\":\"%s\",\"password\":\"Admin#2026x\"}".formatted(next))
        .expect(202);
    assertThat(person.get("/api/v1/auth/security").expect(200).string("$.data.pendingEmail"))
        .isEqualTo(next);
    // Nothing changed yet: the old email still signs in.
    anonymous.signIn(email, "Admin#2026x");

    String token =
        Outbox.token(outbox.platformMessage("access.email_confirm", "EMAIL", next, true));
    anonymous
        .post("/api/v1/auth/email-change/confirm", "{\"token\":\"%s\"}".formatted(token))
        .expect(200);
    anonymous
        .post("/api/v1/auth/email-change/confirm", "{\"token\":\"%s\"}".formatted(token))
        .expect(422);
    anonymous
        .post(
            "/api/v1/auth/sign-in",
            "{\"email\":\"%s\",\"password\":\"Admin#2026x\"}".formatted(email))
        .expect(401);
    Api renamed = anonymous.as(anonymous.signIn(next, "Admin#2026x"));
    assertThat(outbox.platformMessage("access.email_changed", "EMAIL", email, true))
        .contains("ne***@test.local");
    assertThat(history(renamed)).contains("EMAIL_CHANGE_REQUESTED", "EMAIL_CHANGED", "SIGN_IN");
    // The pharmacy's list shows the new address.
    assertThat(renamed.in(tenant, null).get("/api/v1/users").expect(200).body()).contains(next);
  }

  @Test
  @DisplayName(
      "AUTH-13 the old address may undo an email change: back, a new password, every session out")
  void revertEmailChange() {
    String next = "moved." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    person
        .post(
            "/api/v1/auth/email-change",
            "{\"newEmail\":\"%s\",\"password\":\"Admin#2026x\"}".formatted(next))
        .expect(202);
    String confirm =
        Outbox.token(outbox.platformMessage("access.email_confirm", "EMAIL", next, true));
    anonymous
        .post("/api/v1/auth/email-change/confirm", "{\"token\":\"%s\"}".formatted(confirm))
        .expect(200);
    Api mover = anonymous.as(anonymous.signIn(next, "Admin#2026x"));

    // The old address is told, with a link that undoes it.
    String notice = outbox.platformMessage("access.email_changed", "EMAIL", email, true);
    assertThat(notice).contains("/revert-email?token=").contains("7 days");
    String revert = Outbox.token(notice);
    // A confirm link it is not.
    anonymous
        .post("/api/v1/auth/email-change/confirm", "{\"token\":\"%s\"}".formatted(revert))
        .expect(422);

    // Whoever moved the account asks to move it on: the way back stays open.
    String third = "third." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    mover
        .post(
            "/api/v1/auth/email-change",
            "{\"newEmail\":\"%s\",\"password\":\"Admin#2026x\"}".formatted(third))
        .expect(202);
    String onward =
        Outbox.token(outbox.platformMessage("access.email_confirm", "EMAIL", third, true));

    String body = "{\"token\":\"%s\",\"newPassword\":\"%s\"}";
    assertThat(
            anonymous
                .post("/api/v1/auth/email-change/revert", body.formatted(revert, ""))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("password_required");
    anonymous
        .post("/api/v1/auth/email-change/revert", body.formatted(revert, "Back#2026-home"))
        .expect(200);

    // The old address and the new password sign in; the mover's session and address do not.
    mover.get("/api/v1/auth/me").expect(401);
    Api back = anonymous.as(anonymous.signIn(email, "Back#2026-home"));
    anonymous
        .post(
            "/api/v1/auth/sign-in",
            "{\"email\":\"%s\",\"password\":\"Admin#2026x\"}".formatted(next))
        .expect(401);
    // Single use, and the onward change asked for meanwhile is cancelled.
    anonymous
        .post("/api/v1/auth/email-change/revert", body.formatted(revert, "Again#2026-home"))
        .expect(422);
    anonymous
        .post("/api/v1/auth/email-change/confirm", "{\"token\":\"%s\"}".formatted(onward))
        .expect(422);
    assertThat(history(back)).contains("EMAIL_CHANGED", "EMAIL_CHANGE_REVERTED");
  }

  @Test
  @DisplayName("asking for an address that has an account answers the same and changes nothing")
  void takenEmail() {
    String other = "taken." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    root.post(
            "/api/v1/platform/tenants",
            """
            {"code":"%s","name":"Other","legalName":"Other Ltd","country":"TZ","currency":"TZS",
             "timezone":"Africa/Dar_es_Salaam","headOffice":{"code":"HQ","name":"Head Office"},
             "admin":{"name":"Other","email":"%s","password":"Admin#2026x"}}
            """
                .formatted(
                    "TK" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(), other))
        .expect(201);
    person
        .post(
            "/api/v1/auth/email-change",
            "{\"newEmail\":\"%s\",\"password\":\"Admin#2026x\"}".formatted(other))
        .expect(202);
    assertThat(outbox.platformMessage("access.email_in_use", "EMAIL", other, true)).isNotBlank();
    assertThat(outbox.platformMessage("access.email_confirm", "EMAIL", other, false)).isNull();
    assertThat(person.get("/api/v1/auth/security").expect(200).body())
        .contains("\"pendingEmail\":null");
  }

  @Test
  @DisplayName("a phone is verified by its SMS code; a recycled number moves to its new holder")
  void phoneVerification() {
    String phone = newPhone();
    String local = "0" + phone.substring(4);
    person.post("/api/v1/auth/phone", "{\"phone\":\"%s\"}".formatted(local)).expect(202);
    String code = smsCode(phone);
    String wrong = code.equals("000000") ? "111111" : "000000";
    assertThat(
            person
                .post("/api/v1/auth/phone/verify", "{\"code\":\"%s\"}".formatted(wrong))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_code");
    person.post("/api/v1/auth/phone/verify", "{\"code\":\"%s\"}".formatted(code)).expect(200);
    assertThat(person.get("/api/v1/auth/security").expect(200).body())
        .contains("\"phone\":\"" + phone + "\"", "\"phoneVerified\":true");
    // The profile cannot swap it without a code.
    assertThat(
            person
                .put(
                    "/api/v1/auth/profile",
                    "{\"name\":\"Amina\",\"phone\":\"%s\"}".formatted(newPhone()))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("phone_needs_verification");
    person
        .put("/api/v1/auth/profile", "{\"name\":\"Amina H.\",\"phone\":\"%s\"}".formatted(phone))
        .expect(200);

    // A second person is given the same number and verifies it: the first loses it.
    String second = "second." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    root.post(
            "/api/v1/platform/tenants",
            """
            {"code":"%s","name":"Second","legalName":"Second Ltd","country":"TZ","currency":"TZS",
             "timezone":"Africa/Dar_es_Salaam","headOffice":{"code":"HQ","name":"Head Office"},
             "admin":{"name":"Baraka","email":"%s","password":"Admin#2026x"}}
            """
                .formatted(
                    "SC" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(), second))
        .expect(201);
    Api other = anonymous.as(anonymous.signIn(second, "Admin#2026x"));
    other.post("/api/v1/auth/phone", "{\"phone\":\"%s\"}".formatted(phone)).expect(202);
    String otherCode =
        Api.eventuallyValue(
            () -> {
              String latest = smsCode(phone);
              return latest.equals(code) ? null : latest;
            });
    other.post("/api/v1/auth/phone/verify", "{\"code\":\"%s\"}".formatted(otherCode)).expect(200);
    assertThat(person.get("/api/v1/auth/security").expect(200).body())
        .contains("\"phone\":null", "\"phoneVerified\":false");
    assertThat(outbox.platformMessage("access.phone_removed", "EMAIL", email, true))
        .contains(phone);
    assertThat(history(person)).contains("PHONE_VERIFIED", "PHONE_LOST");
  }

  @Test
  @DisplayName(
      "a verified phone resets the password; other sessions end; unknown numbers learn nothing")
  void resetByPhone() {
    String phone = newPhone();
    person.post("/api/v1/auth/phone", "{\"phone\":\"%s\"}".formatted(phone)).expect(202);
    person
        .post("/api/v1/auth/phone/verify", "{\"code\":\"%s\"}".formatted(smsCode(phone)))
        .expect(200);
    String verifyCode = smsCode(phone);

    String stranger = newPhone();
    anonymous
        .post("/api/v1/auth/password-reset/phone", "{\"phone\":\"%s\"}".formatted(stranger))
        .expect(202);
    anonymous
        .post("/api/v1/auth/password-reset/phone", "{\"phone\":\"%s\"}".formatted(phone))
        .expect(202);
    String code =
        Api.eventuallyValue(
            () -> {
              String latest = smsCode(phone);
              return latest.equals(verifyCode) ? null : latest;
            });
    assertThat(outbox.platformMessage("access.verification_code", "SMS", stranger, false)).isNull();
    anonymous
        .post(
            "/api/v1/auth/password-reset/phone/complete",
            "{\"phone\":\"%s\",\"code\":\"%s\",\"newPassword\":\"Fresh#2026xyz\"}"
                .formatted(phone, code))
        .expect(200);
    person.get("/api/v1/auth/me").expect(401);
    anonymous.signIn(email, "Fresh#2026xyz");
    assertThat(outbox.platformMessage("access.password_changed", "EMAIL", email, true))
        .isNotBlank();
  }

  @Test
  @DisplayName(
      "assisted recovery waits 24 hours, can be cancelled by the old owner, then moves the account")
  void assistedRecovery() throws Exception {
    String fresh = "fresh." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    String uid =
        root.get("/api/v1/platform/accounts?email=" + email).expect(200).string("$.data.uid");
    Api.Response started =
        root.post(
                "/api/v1/platform/accounts/" + uid + "/recovery",
                "{\"newEmail\":\"%s\",\"resetTwoFactor\":true,\"reason\":\"NIDA ID seen by owner\"}"
                    .formatted(fresh))
            .expect(201);
    Instant usable = Instant.parse(started.string("$.data.usableFrom"));
    assertThat(Duration.between(Instant.now(), usable)).isGreaterThan(Duration.ofHours(23));

    String notice = outbox.platformMessage("access.recovery_started", "EMAIL", email, true);
    String link = outbox.platformMessage("access.recovery_link", "EMAIL", fresh, true);
    String token = Outbox.token(link);
    assertThat(
            anonymous
                .post(
                    "/api/v1/auth/email-change/confirm",
                    "{\"token\":\"%s\",\"newPassword\":\"Back#2026xyz\"}".formatted(token))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("recovery_on_hold");

    // The old owner cancels it from their notice: the link is dead.
    anonymous
        .post("/api/v1/auth/recovery/cancel", "{\"token\":\"%s\"}".formatted(Outbox.token(notice)))
        .expect(200);
    holdOver();
    anonymous
        .post(
            "/api/v1/auth/email-change/confirm",
            "{\"token\":\"%s\",\"newPassword\":\"Back#2026xyz\"}".formatted(token))
        .expect(422);

    // Started again and left alone, after the hold it moves the account and sets the password.
    root.post(
            "/api/v1/platform/accounts/" + uid + "/recovery",
            "{\"newEmail\":\"%s\",\"resetTwoFactor\":false,\"reason\":\"second visit\"}"
                .formatted(fresh))
        .expect(201);
    String second =
        Api.eventuallyValue(
            () -> {
              String t =
                  Outbox.token(
                      outbox.platformMessage("access.recovery_link", "EMAIL", fresh, true));
              return t.equals(token) ? null : t;
            });
    holdOver();
    anonymous
        .post(
            "/api/v1/auth/email-change/confirm",
            "{\"token\":\"%s\",\"newPassword\":\"Back#2026xyz\"}".formatted(second))
        .expect(200);
    person.get("/api/v1/auth/me").expect(401);
    Api back = anonymous.as(anonymous.signIn(fresh, "Back#2026xyz"));
    assertThat(history(back))
        .contains("RECOVERY_STARTED", "RECOVERY_CANCELLED", "RECOVERY_COMPLETED");
  }

  @Test
  @DisplayName("staff logins have no email change, phone or recovery of their own")
  void managedLoginsRefused() {
    Api admin = person.in(tenant, null);
    Staff.Login juma = Staff.login(admin, anonymous, "Juma", "CASHIER", "Juma#2026xyz");
    Api staff = anonymous.as(anonymous.signIn(juma.signIn(), "Juma#2026xyz"));
    assertThat(
            staff
                .post(
                    "/api/v1/auth/email-change",
                    "{\"newEmail\":\"juma@test.local\",\"password\":\"Juma#2026xyz\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("managed_login");
    assertThat(
            staff
                .post("/api/v1/auth/phone", "{\"phone\":\"%s\"}".formatted(newPhone()))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("managed_login");
  }

  /** Ends every recovery hold now, as if 24 hours had passed. */
  private void holdOver() throws Exception {
    try (Connection connection = DriverManager.getConnection(url, owner, ownerPassword);
        PreparedStatement update =
            connection.prepareStatement(
                "update access.email_changes set not_before = now() - interval '1 minute'"
                    + " where not_before > now()")) {
      update.executeUpdate();
    }
  }
}
