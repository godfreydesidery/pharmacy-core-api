package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Outbox;
import tz.co.pharmacy.support.Phones;
import tz.co.pharmacy.support.Staff;

/**
 * One person working in two pharmacies that do not know each other (gaps.md §9.8, step 4): each
 * pharmacy sees sign-ins and activity in it only, none of what happens to the person's account, no
 * way to unlock it, and, once the person leaves, them as they were then.
 */
class PharmaciesApartTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api anonymous;
  private Api root;
  private String weekday;
  private String weekend;
  private Api weekdayAdmin;
  private Api weekendAdmin;
  private String juma;
  private String membership;

  @BeforeEach
  void twoPharmacies() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    juma = "juma." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    String other = "rehema." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    // Juma runs the weekday pharmacy; on weekends he works at another, whose admin is Rehema.
    weekday = register(juma);
    weekend = register(other);
    weekdayAdmin = anonymous.as(anonymous.signIn(juma, "Admin#2026x-strong")).in(weekday, null);
    weekendAdmin = anonymous.as(anonymous.signIn(other, "Admin#2026x-strong")).in(weekend, null);
    List<String> roles =
        weekendAdmin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == 'PHARMACIST')].uid");
    weekendAdmin
        .post(
            "/api/v1/users",
            "{\"name\":\"Juma\",\"email\":\"%s\",\"roleUid\":\"%s\",\"allBranches\":true}"
                .formatted(juma, roles.getFirst()))
        .expect(202);
    String token = new Outbox(url, owner, ownerPassword).invitationToken(weekend, juma);
    anonymous
        .as(anonymous.signIn(juma, "Admin#2026x-strong"))
        .post("/api/v1/auth/invitations/join", "{\"token\":\"%s\"}".formatted(token))
        .expect(200);
    List<String> uids =
        weekendAdmin
            .get("/api/v1/users?q=" + juma)
            .expect(200)
            .json()
            .read("$.data.items[*].membershipUid");
    membership = uids.getFirst();
  }

  private String register(String adminEmail) {
    String code = "PA" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    return root.post(
            "/api/v1/platform/tenants",
            """
            {"code":"%s","name":"Apart %s","legalName":"Apart %s Ltd","country":"TZ",
             "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
             "headOffice":{"code":"HQ","name":"Head Office"},
             "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
            """
                .formatted(code, code, code, adminEmail))
        .expect(201)
        .string("$.data.uid");
  }

  private List<String> signInsOf(Api admin, String email) {
    return admin
        .get("/api/v1/audit?action=SIGN_IN&limit=100")
        .expect(200)
        .json()
        .read("$.data.items[?(@.actor == '" + email + "')].action");
  }

  private Map<String, Object> member(Api admin, String membershipUid) {
    List<Map<String, Object>> found =
        admin
            .get("/api/v1/users?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[?(@.membershipUid == '" + membershipUid + "')]");
    return found.getFirst();
  }

  @Test
  @DisplayName("a pharmacy records sign-ins and activity in it, never those elsewhere")
  void onlyWorkHereIsSeen() {
    Api session = anonymous.as(anonymous.signIn(juma, "Admin#2026x-strong"));
    // Weekday work: the weekend pharmacy learns nothing of it.
    session.in(weekday, null).get("/api/v1/products").expect(200);
    session.in(weekday, null).get("/api/v1/settings").expect(200);
    assertThat(signInsOf(weekendAdmin, juma)).isEmpty();
    assertThat(member(weekendAdmin, membership).get("lastActiveAt")).isNull();
    assertThat(signInsOf(weekdayAdmin, juma)).isNotEmpty();

    // Saturday: one sign-in recorded there, however many requests follow.
    session.in(weekend, null).get("/api/v1/products").expect(200);
    session.in(weekend, null).get("/api/v1/settings").expect(200);
    assertThat(signInsOf(weekendAdmin, juma)).hasSize(1);
    assertThat(member(weekendAdmin, membership).get("lastActiveAt")).isNotNull();
    // Keeping "last active here" leaves no entries in the pharmacy's audit.
    assertThat(weekendAdmin.get("/api/v1/audit?entityType=Membership&limit=100").expect(200).body())
        .doesNotContain("lastActiveAt");

    // Signing out is recorded where the session worked.
    session.post("/api/v1/auth/sign-out", null).expect(200);
    String signOuts = weekendAdmin.get("/api/v1/audit?action=SIGN_OUT&limit=50").expect(200).body();
    assertThat(signOuts).contains(juma);
  }

  @Test
  @DisplayName("what happens to the person's account stays out of every pharmacy's audit")
  void accountEventsStayOut() {
    // Changing the password while the app is in the weekday pharmacy.
    Api session = anonymous.as(anonymous.signIn(juma, "Admin#2026x-strong")).in(weekday, null);
    session
        .post(
            "/api/v1/auth/change-password",
            "{\"currentPassword\":\"Admin#2026x-strong\",\"newPassword\":\"Changed#2026x-strong\"}")
        .expect(200);
    session.post("/api/v1/auth/2fa/setup", "{}").expect(200);
    // The change signed Juma's other sessions out, his weekday admin one too.
    weekdayAdmin.get("/api/v1/settings").expect(401);
    weekdayAdmin = anonymous.as(anonymous.signIn(juma, "Changed#2026x-strong")).in(weekday, null);
    for (Api admin : List.of(weekdayAdmin, weekendAdmin)) {
      assertThat(admin.get("/api/v1/audit?limit=100").expect(200).body())
          .doesNotContain("PASSWORD_CHANGED");
    }
    List<String> platform =
        root.get("/api/v1/platform/audit?action=PASSWORD_CHANGED&limit=50")
            .expect(200)
            .json()
            .read("$.data.items[*].actor");
    assertThat(platform).contains(juma);
  }

  @Test
  @DisplayName("a pharmacy unlocks its own staff logins, never a person's own account")
  void unlockOnlyOwnLogins() {
    assertThat(
            weekendAdmin
                .post("/api/v1/users/" + membership + "/unlock", "{}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("not_managed");
    Staff.Login cashier =
        Staff.login(weekendAdmin, anonymous, "Neema", "CASHIER", "Staff#2026x-strong");
    weekendAdmin.post("/api/v1/users/" + cashier.membershipUid() + "/unlock", "{}").expect(200);
  }

  @Test
  @DisplayName("a former employer sees the person as they were when they left")
  void formerEmployerKeepsTheSnapshot() {
    List<String> roles =
        weekendAdmin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == 'PHARMACIST')].uid");
    String role = roles.getFirst();
    Api session = anonymous.as(anonymous.signIn(juma, "Admin#2026x-strong"));
    Outbox outbox = new Outbox(url, owner, ownerPassword);
    String first = Phones.fresh();
    String second = Phones.fresh();
    session.put("/api/v1/auth/profile", "{\"name\":\"Juma Ali\"}").expect(200);
    Phones.verify(session, outbox, first);

    // He leaves the weekend pharmacy, then changes his name and phone.
    weekendAdmin
        .put(
            "/api/v1/users/" + membership,
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":false}".formatted(role))
        .expect(200);
    session.put("/api/v1/auth/profile", "{\"name\":\"Juma Mwinyi\"}").expect(200);
    Phones.verify(session, outbox, second);

    Map<String, Object> former = member(weekendAdmin, membership);
    assertThat(former.get("name")).isEqualTo("Juma Ali");
    assertThat(former.get("phone")).isEqualTo(first);
    // Found by what the pharmacy kept, not by what changed since.
    Response byNew = weekendAdmin.get("/api/v1/users?q=Mwinyi").expect(200);
    assertThat(byNew.read("$.data.items.length()", Integer.class)).isZero();
    Response byOld = weekendAdmin.get("/api/v1/users?q=Juma%20Ali").expect(200);
    assertThat(byOld.read("$.data.items.length()", Integer.class)).isEqualTo(1);
    // The pharmacy he still works for sees him as he is.
    List<String> names =
        weekdayAdmin.get("/api/v1/users").expect(200).json().read("$.data.items[*].name");
    assertThat(names).contains("Juma Mwinyi");

    // Back at the weekend pharmacy: seen as he is now.
    weekendAdmin
        .put(
            "/api/v1/users/" + membership,
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":true}".formatted(role))
        .expect(200);
    assertThat(member(weekendAdmin, membership).get("name")).isEqualTo("Juma Mwinyi");
  }
}
