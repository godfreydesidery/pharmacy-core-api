package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Outbox;
import tz.co.pharmacy.support.Staff;

/**
 * A person links a pharmacy's staff login to their personal account (G22): only that person,
 * proving both; the membership moves; the login stops; the pharmacy learns nothing of the account's
 * other pharmacies.
 */
class LinkStaffLoginTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api anonymous;
  private Api root;
  private String weekday;
  private Api weekdayAdmin;
  private String weekend;
  private Api weekendAdmin;
  private String personEmail;
  private Api person;

  private String pharmacy(String prefix, String adminEmail) {
    String code = prefix + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    return root.post(
            "/api/v1/platform/tenants",
            """
            {"code":"%s","name":"Link %s","legalName":"Link %s Ltd","country":"TZ",
             "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
             "headOffice":{"code":"HQ","name":"Head Office"},
             "admin":{"name":"Juma Ali","email":"%s","password":"Admin#2026x"}}
            """
                .formatted(code, code, code, adminEmail))
        .expect(201)
        .string("$.data.uid");
  }

  @BeforeEach
  void pharmacies() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    // Juma owns a weekday pharmacy (his personal account)...
    personEmail = "juma." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    weekday = pharmacy("WD", personEmail);
    person = anonymous.as(anonymous.signIn(personEmail, "Admin#2026x"));
    weekdayAdmin = person.in(weekday, null);
    // ...and has a staff login at a weekend pharmacy that does not know him otherwise.
    String weekendOwner = "owner." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    weekend = pharmacy("WE", weekendOwner);
    weekendAdmin = anonymous.as(anonymous.signIn(weekendOwner, "Admin#2026x")).in(weekend, null);
  }

  private String link(Api who, String signIn, String loginPassword, String password) {
    return who.post(
            "/api/v1/auth/link-login",
            """
            {"signIn":"%s","loginPassword":"%s","password":"%s"}
            """
                .formatted(signIn, loginPassword, password))
        .body();
  }

  @Test
  @DisplayName(
      "the person links the login with both passwords; the membership moves, the login stops")
  void linking() {
    Staff.Login login = Staff.login(weekendAdmin, anonymous, "Juma Ali", "CASHIER", "Juma#2026xyz");
    Api staffSession = anonymous.as(anonymous.signIn(login.signIn(), "Juma#2026xyz"));

    assertThat(link(person, login.signIn(), "wrong-password", "Admin#2026x"))
        .contains("invalid_login");
    assertThat(link(person, "nobody@" + login.signIn().split("@")[1], "x", "Admin#2026x"))
        .contains("invalid_login");
    assertThat(link(person, login.signIn(), "Juma#2026xyz", "not-mine"))
        .contains("invalid_password");
    // A staff login cannot link itself (or anything) to anything.
    assertThat(link(staffSession, login.signIn(), "Juma#2026xyz", "Juma#2026xyz"))
        .contains("managed_login");

    person
        .post(
            "/api/v1/auth/link-login",
            """
            {"signIn":"%s","loginPassword":"Juma#2026xyz","password":"Admin#2026x"}
            """
                .formatted(login.signIn().toUpperCase()))
        .expect(200);

    // Juma now reaches the weekend pharmacy with his own account, in the same role.
    List<String> tenants =
        person.get("/api/v1/auth/me").expect(200).json().read("$.data.memberships[*].tenantUid");
    assertThat(tenants).contains(weekday, weekend);
    Api.Response members = weekendAdmin.get("/api/v1/users").expect(200);
    List<String> emails = members.json().read("$.data.items[*].email");
    assertThat(emails).contains(personEmail).doesNotContain(login.signIn().toLowerCase());
    List<String> roles =
        members.json().read("$.data.items[?(@.email == '" + personEmail + "')].roleCode");
    assertThat(roles).containsExactly("CASHIER");

    // The staff login is over: its session ended; its password says where it went.
    staffSession.get("/api/v1/auth/me").expect(401);
    assertThat(
            anonymous
                .post(
                    "/api/v1/auth/sign-in",
                    "{\"email\":\"%s\",\"password\":\"Juma#2026xyz\"}".formatted(login.signIn()))
                .expect(401)
                .string("$.reason"))
        .isEqualTo("login_linked");
    assertThat(
            anonymous
                .post(
                    "/api/v1/auth/sign-in",
                    "{\"email\":\"%s\",\"password\":\"guess-123\"}".formatted(login.signIn()))
                .expect(401)
                .string("$.reason"))
        .isEqualTo("invalid_credentials");

    // The weekend pharmacy's audit says the login was linked; the weekday one's says nothing.
    assertThat(weekendAdmin.get("/api/v1/audit?action=STAFF_LOGIN_LINKED").expect(200).body())
        .contains(login.signIn().toLowerCase());
    assertThat(
            weekdayAdmin
                .get("/api/v1/audit?action=STAFF_LOGIN_LINKED")
                .expect(200)
                .read("$.data.items.length()", Integer.class))
        .isZero();
    // Juma's own history and mailbox say so too.
    List<String> history =
        person
            .get("/api/v1/auth/security/history?limit=20")
            .expect(200)
            .json()
            .read("$.data.items[*].action");
    assertThat(history).contains("STAFF_LOGIN_LINKED");
    assertThat(
            new Outbox(url, owner, ownerPassword)
                .platformMessage("access.login_linked", "EMAIL", personEmail, true))
        .contains(login.signIn().toLowerCase());
  }

  @Test
  @DisplayName("a pharmacy the account is already in cannot have a second membership by linking")
  void alreadyAMember() {
    Staff.Login own = Staff.login(weekdayAdmin, anonymous, "Juma Two", "CASHIER", "Juma#2026xyz");
    assertThat(link(person, own.signIn(), "Juma#2026xyz", "Admin#2026x"))
        .contains("already_a_member");
    // Nothing moved: the login still signs in.
    anonymous.signIn(own.signIn(), "Juma#2026xyz");
  }
}
