package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Outbox;
import tz.co.pharmacy.support.Staff;

/**
 * Adding a person by email (gaps.md §9.8, step 3): always an invitation, answered the same whether
 * or not the email has an account; a new person accepts with a new account, someone with an account
 * signs in and joins with it (whatever its email); a pharmacy's staff login joins nowhere else; and
 * a new pharmacy whose administrator already has an account invites them.
 */
class InvitationsTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api anonymous;
  private Api root;
  private Api admin;
  private String tenant;
  private String cashierRole;
  private Outbox outbox;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String email = "inv." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    tenant = register(email).string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    List<String> roles =
        admin.get("/api/v1/roles").expect(200).json().read("$.data[?(@.code == 'CASHIER')].uid");
    cashierRole = roles.getFirst();
    outbox = new Outbox(url, owner, ownerPassword);
  }

  private Response register(String adminEmail) {
    String code = "IV" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    return root.post(
            "/api/v1/platform/tenants",
            """
            {"code":"%s","name":"Invite %s","legalName":"Invite %s Ltd","country":"TZ",
             "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
             "headOffice":{"code":"HQ","name":"Head Office"},
             "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
            """
                .formatted(code, code, code, adminEmail))
        .expect(201);
  }

  private Response add(String name, String email) {
    return admin.post(
        "/api/v1/users",
        """
        {"name":"%s","email":"%s","password":"Chosen#2026x-strong","roleUid":"%s","allBranches":true}
        """
            .formatted(name, email, cashierRole));
  }

  private String tenantsOf(Api person) {
    return person.get("/api/v1/auth/me").expect(200).body();
  }

  @Test
  @DisplayName("adding by email always invites, and says nothing of other pharmacies")
  void alwaysInvites() {
    // A new email: invited, not created with the admin's password.
    String fresh = "new." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    Response forNew = add("Rehema", fresh).expect(202);
    anonymous
        .post(
            "/api/v1/auth/sign-in",
            "{\"email\":\"" + fresh + "\",\"password\":\"Chosen#2026x-strong\"}")
        .expect(401);

    // An email with an account in another pharmacy: the very same answer.
    String elsewhere = "else." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    register(elsewhere);
    Response forExisting = add("Baraka", elsewhere).expect(202);
    assertThat(forExisting.read("$.data.invited", Boolean.class)).isTrue();
    assertThat(forNew.json().read("$.data.*", List.class).size())
        .isEqualTo(forExisting.json().read("$.data.*", List.class).size());
    assertThat(forNew.string("$.message")).isEqualTo(forExisting.string("$.message"));
  }

  @Test
  @DisplayName("a new person accepts with a new account; an existing one must sign in to join")
  void newAccountAccepts() {
    String fresh = "new." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    add("Rehema", fresh).expect(202);
    String token = outbox.invitationToken(tenant, fresh);
    Response accepted =
        anonymous
            .post(
                "/api/v1/auth/invitations/accept",
                "{\"token\":\"%s\",\"name\":\"Rehema M\",\"password\":\"Mine#2026xx-strong\"}"
                    .formatted(token))
            .expect(201);
    assertThat(accepted.string("$.data.memberships[0].tenantUid")).isEqualTo(tenant);

    // An email that has an account cannot be accepted without signing in.
    String elsewhere = "else." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    register(elsewhere);
    add("Baraka", elsewhere).expect(202);
    String other = outbox.invitationToken(tenant, elsewhere);
    assertThat(
            anonymous
                .post(
                    "/api/v1/auth/invitations/accept",
                    "{\"token\":\"%s\",\"password\":\"Admin#2026x-strong\"}".formatted(other))
                .expect(409)
                .string("$.reason"))
        .isEqualTo("account_exists");
    Api baraka = anonymous.as(anonymous.signIn(elsewhere, "Admin#2026x-strong"));
    baraka.post("/api/v1/auth/invitations/join", "{\"token\":\"%s\"}".formatted(other)).expect(200);
    assertThat(tenantsOf(baraka)).contains(tenant);
  }

  @Test
  @DisplayName("invited at one email, the person joins with their account under another")
  void joinsWithAnotherEmail() {
    String invited = "juma." + UUID.randomUUID().toString().substring(0, 6) + "@gmail.test";
    add("Juma", invited).expect(202);
    String token = outbox.invitationToken(tenant, invited);

    String own = "juma.work." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    register(own);
    Api juma = anonymous.as(anonymous.signIn(own, "Admin#2026x-strong"));
    Response joined =
        juma.post("/api/v1/auth/invitations/join", "{\"token\":\"%s\"}".formatted(token))
            .expect(200);
    assertThat(joined.string("$.data.tenantUid")).isEqualTo(tenant);
    assertThat(joined.string("$.data.roleName")).isEqualTo("Cashier");
    assertThat(tenantsOf(juma)).contains(tenant);

    // The pharmacy sees which account accepted; the token works once.
    List<String> acceptedAs =
        admin
            .get("/api/v1/users/invitations")
            .expect(200)
            .json()
            .read("$.data.items[?(@.email == '" + invited + "')].acceptedAs");
    assertThat(acceptedAs).containsExactly(own);
    assertThat(
            juma.post("/api/v1/auth/invitations/join", "{\"token\":\"%s\"}".formatted(token))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invitation_not_open");
  }

  @Test
  @DisplayName("a pharmacy's staff login cannot join another pharmacy")
  void managedLoginCannotJoin() {
    String other = "oth." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    String otherTenant = register(other).string("$.data.uid");
    Api otherAdmin =
        anonymous.as(anonymous.signIn(other, "Admin#2026x-strong")).in(otherTenant, null);
    Staff.Login login =
        Staff.login(otherAdmin, anonymous, "Neema", "CASHIER", "Staff#2026x-strong");

    String invited = "neema." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    add("Neema", invited).expect(202);
    String token = outbox.invitationToken(tenant, invited);
    Api neema = anonymous.as(anonymous.signIn(login.signIn(), "Staff#2026x-strong"));
    assertThat(
            neema
                .post("/api/v1/auth/invitations/join", "{\"token\":\"%s\"}".formatted(token))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("managed_login_cannot_join");
    assertThat(tenantsOf(neema)).doesNotContain(tenant);
  }

  @Test
  @DisplayName("a new pharmacy whose administrator has an account invites them instead")
  void onboardingInvitesAnExistingAdmin() {
    String existing = "owner." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    register(existing);
    Api person = anonymous.as(anonymous.signIn(existing, "Admin#2026x-strong"));

    // Registering a second pharmacy for the same email, with another password.
    String code = "IW" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    Response second =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Second %s","legalName":"Second %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Owner","email":"%s","password":"Other#2026xx-strong"}}
                """
                    .formatted(code, code, code, existing))
            .expect(201);
    String secondTenant = second.string("$.data.uid");
    assertThat(second.string("$.data.code")).isEqualTo(code);
    assertThat(second.read("$.data.adminInvited", Boolean.class)).isTrue();
    // Not a member until they accept, and their password is unchanged.
    assertThat(tenantsOf(person)).doesNotContain(secondTenant);
    anonymous
        .post(
            "/api/v1/auth/sign-in",
            "{\"email\":\"" + existing + "\",\"password\":\"Other#2026xx-strong\"}")
        .expect(401);

    String token = outbox.invitationToken(secondTenant, existing);
    person.post("/api/v1/auth/invitations/join", "{\"token\":\"%s\"}".formatted(token)).expect(200);
    List<String> roles =
        person
            .get("/api/v1/auth/me")
            .expect(200)
            .json()
            .read("$.data.memberships[?(@.tenantUid == '" + secondTenant + "')].roleCode");
    assertThat(roles).containsExactly("ADMIN");
    // A brand-new admin email is still made at once.
    assertThat(
            register("fresh." + UUID.randomUUID() + "@test.local")
                .read("$.data.adminInvited", Boolean.class))
        .isFalse();
  }
}
