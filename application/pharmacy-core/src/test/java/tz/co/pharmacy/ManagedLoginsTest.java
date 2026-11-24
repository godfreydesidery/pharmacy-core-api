package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Managed logins: staff logins a pharmacy's admin creates directly (for cashiers with no email).
 * They sign in as username@CODE on a temporary password they must change first; the admin may reset
 * them; and the root administrator decides whether a pharmacy may create them at all.
 */
class ManagedLoginsTest extends IntegrationTest {

  private Api anonymous;
  private Api root;
  private Api admin;
  private String tenant;
  private String code;
  private String cashierRole;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    code = "ML" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Managed %s","legalName":"Managed %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    List<String> roles =
        admin.get("/api/v1/roles").expect(200).json().read("$.data[?(@.code == 'CASHIER')].uid");
    cashierRole = roles.getFirst();
  }

  private Response create(String username) {
    return admin.post(
        "/api/v1/users/managed",
        """
        {"username":"%s","name":"Juma Ali","roleUid":"%s","allBranches":true,
         "phone":"+255754000111","contactEmail":"Juma.Ali@Mail.test"}
        """
            .formatted(username, cashierRole));
  }

  @Test
  @DisplayName("a cashier without email: created, signs in as juma@CODE, changes the password")
  void createSignInAndChange() {
    assertThat(
            admin
                .get("/api/v1/auth/me")
                .expect(200)
                .read("$.data.memberships[0].directUserCreation", Boolean.class))
        .isTrue();
    Response created = create("Juma").expect(201);
    String signIn = created.string("$.data.signIn");
    String temporary = created.string("$.data.temporaryPassword");
    assertThat(signIn).isEqualTo("juma@" + code.toLowerCase());
    assertThat(temporary).matches("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$").doesNotContainPattern("[0O1IL5S8B]");
    assertThat(created.read("$.data.member.managed", Boolean.class)).isTrue();
    assertThat(created.string("$.data.member.contactEmail")).isEqualTo("juma.ali@mail.test");
    assertThat(created.read("$.data.member.passwordChangeRequired", Boolean.class)).isTrue();

    assertThat(create("juma").expect(409).string("$.reason")).isEqualTo("username_taken");
    assertThat(create("j").expect(422).string("$.reason")).isEqualTo("invalid_username");
    assertThat(create("ju ma").expect(422).string("$.reason")).isEqualTo("invalid_username");

    // Signs in with the pharmacy code in capitals, as printed on the shop's sign.
    Response session =
        anonymous
            .post(
                "/api/v1/auth/sign-in",
                "{\"email\":\"Juma@" + code + "\",\"password\":\"" + temporary + "\"}")
            .expect(201);
    assertThat(session.read("$.data.user.passwordChangeRequired", Boolean.class)).isTrue();
    Api juma = anonymous.as(session.string("$.data.token")).in(tenant, null);
    assertThat(juma.get("/api/v1/products").expect(403).string("$.reason"))
        .isEqualTo("password_change_required");
    assertThat(
            juma.post(
                    "/api/v1/auth/change-password",
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(temporary, temporary))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("same_password");
    juma.post(
            "/api/v1/auth/change-password",
            "{\"currentPassword\":\"%s\",\"newPassword\":\"Kariakoo#2026-strong\"}"
                .formatted(temporary))
        .expect(200);
    juma.get("/api/v1/products").expect(200);
    assertThat(
            juma.get("/api/v1/auth/me")
                .expect(200)
                .read("$.data.user.passwordChangeRequired", Boolean.class))
        .isFalse();

    // No reset link for a managed login: same answer as anyone, and no mail to juma@code.
    anonymous.post("/api/v1/auth/password-reset", "{\"email\":\"" + signIn + "\"}").expect(202);
  }

  @Test
  @DisplayName("the admin resets a managed login, never a person's own account")
  void adminReset() {
    Response created = create("neema").expect(201);
    String membership = created.string("$.data.member.membershipUid");
    String signIn = created.string("$.data.signIn");
    String first = created.string("$.data.temporaryPassword");
    Api neema = anonymous.as(anonymous.signIn(signIn, first));
    neema
        .post(
            "/api/v1/auth/change-password",
            "{\"currentPassword\":\"%s\",\"newPassword\":\"Arusha#2026x-strong\"}".formatted(first))
        .expect(200);

    Response reset =
        admin.post("/api/v1/users/" + membership + "/reset-password", null).expect(200);
    String second = reset.string("$.data.temporaryPassword");
    assertThat(second).isNotEqualTo(first);
    // The old session ends; the new password works once and must be changed.
    neema.get("/api/v1/auth/me").expect(401);
    Response again =
        anonymous
            .post(
                "/api/v1/auth/sign-in",
                "{\"email\":\"" + signIn + "\",\"password\":\"" + second + "\"}")
            .expect(201);
    assertThat(again.read("$.data.user.passwordChangeRequired", Boolean.class)).isTrue();

    // The admin's own account is a person's: only they change its password.
    List<String> own =
        admin
            .get("/api/v1/users")
            .expect(200)
            .json()
            .read("$.data.items[?(@.managed == false)].membershipUid");
    assertThat(
            admin
                .post("/api/v1/users/" + own.getFirst() + "/reset-password", null)
                .expect(422)
                .string("$.reason"))
        .isEqualTo("not_managed");
  }

  @Test
  @DisplayName("the pharmacy keeps a managed login's details; deactivating it ends its sessions")
  void editAndDeactivate() {
    Response created = create("baraka").expect(201);
    String membership = created.string("$.data.member.membershipUid");
    Api baraka =
        anonymous.as(
            anonymous.signIn(
                created.string("$.data.signIn"), created.string("$.data.temporaryPassword")));
    Response changed =
        admin
            .put(
                "/api/v1/users/" + membership,
                """
                {"roleUid":"%s","allBranches":true,"active":true,"name":"Baraka Mushi",
                 "phone":"+255754000222","contactEmail":"baraka@mail.test"}
                """
                    .formatted(cashierRole))
            .expect(200);
    assertThat(changed.string("$.data.name")).isEqualTo("Baraka Mushi");
    assertThat(changed.string("$.data.phone")).isEqualTo("+255754000222");
    assertThat(changed.string("$.data.contactEmail")).isEqualTo("baraka@mail.test");

    admin
        .put(
            "/api/v1/users/" + membership,
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":false}".formatted(cashierRole))
        .expect(200);
    baraka.get("/api/v1/auth/me").expect(401);
  }

  @Test
  @DisplayName("a pharmacy the root administrator has not allowed may only invite")
  void rootTurnsDirectCreationOff() {
    Map<String, Object> pharmacy =
        root.get("/api/v1/platform/tenants/" + tenant).expect(200).json().read("$.data");
    assertThat(pharmacy.get("directUserCreation")).isEqualTo(true);
    root.put(
            "/api/v1/platform/tenants/" + tenant,
            """
            {"name":"%s","legalName":"%s","country":"TZ","timezone":"Africa/Dar_es_Salaam",
             "directUserCreation":false}
            """
                .formatted(pharmacy.get("name"), pharmacy.get("legalName")))
        .expect(200);
    assertThat(
            admin
                .get("/api/v1/auth/me")
                .expect(200)
                .read("$.data.memberships[0].directUserCreation", Boolean.class))
        .isFalse();
    assertThat(create("juma").expect(403).string("$.reason")).isEqualTo("direct_creation_disabled");
    // Even a new email is invited rather than created with an admin-chosen password.
    String email = "new." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    Response added =
        admin
            .post(
                "/api/v1/users",
                """
                {"name":"Rehema","email":"%s","password":"Staff#2026x-strong","roleUid":"%s",
                 "allBranches":true}
                """
                    .formatted(email, cashierRole))
            .expect(202);
    assertThat(added.read("$.data.invited", Boolean.class)).isTrue();
    anonymous
        .post(
            "/api/v1/auth/sign-in",
            "{\"email\":\"" + email + "\",\"password\":\"Staff#2026x-strong\"}")
        .expect(401);

    // A field left out of an edit keeps the setting.
    root.put(
            "/api/v1/platform/tenants/" + tenant,
            """
            {"name":"%s","legalName":"%s","country":"TZ","timezone":"Africa/Dar_es_Salaam"}
            """
                .formatted(pharmacy.get("name"), pharmacy.get("legalName")))
        .expect(200);
    assertThat(
            root.get("/api/v1/platform/tenants/" + tenant)
                .expect(200)
                .read("$.data.directUserCreation", Boolean.class))
        .isFalse();
  }
}
