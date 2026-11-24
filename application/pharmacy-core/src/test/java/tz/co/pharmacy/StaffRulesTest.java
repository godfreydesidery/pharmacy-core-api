package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Outbox;
import tz.co.pharmacy.support.Staff;

/**
 * A person who works in two pharmacies (G14–G17): leaving one themselves; the pharmacy's own staff
 * details; a pharmacy that requires two-factor; the strictest inactivity limit of the two.
 */
class StaffRulesTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api anonymous;
  private Api root;
  private String own;
  private String other;
  private Api otherAdmin;
  private String personEmail;
  private Api person;
  private String membership;

  private String pharmacy(String prefix, String adminEmail) {
    String code = prefix + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    return root.post(
            "/api/v1/platform/tenants",
            """
            {"code":"%s","name":"Rules %s","legalName":"Rules %s Ltd","country":"TZ",
             "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
             "headOffice":{"code":"HQ","name":"Head Office"},
             "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
            """
                .formatted(code, code, code, adminEmail))
        .expect(201)
        .string("$.data.uid");
  }

  @BeforeEach
  void twoPharmacies() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    personEmail = "neema." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    own = pharmacy("OW", personEmail);
    person = anonymous.as(anonymous.signIn(personEmail, "Admin#2026x-strong"));
    String otherOwner = "owner." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    other = pharmacy("OT", otherOwner);
    otherAdmin = anonymous.as(anonymous.signIn(otherOwner, "Admin#2026x-strong")).in(other, null);
    // The other pharmacy invites Neema as a pharmacist; she joins with her own account.
    List<String> roles =
        otherAdmin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == 'PHARMACIST')].uid");
    otherAdmin
        .post(
            "/api/v1/users",
            """
            {"name":"Neema","email":"%s","roleUid":"%s","allBranches":true}
            """
                .formatted(personEmail, roles.getFirst()))
        .expect(202);
    String token = new Outbox(url, owner, ownerPassword).invitationToken(other, personEmail);
    person.post("/api/v1/auth/invitations/join", "{\"token\":\"%s\"}".formatted(token)).expect(200);
    List<String> mine =
        otherAdmin
            .get("/api/v1/users?q=" + personEmail)
            .expect(200)
            .json()
            .read("$.data.items[*].membershipUid");
    membership = mine.getFirst();
  }

  @Test
  @DisplayName("G14 a person leaves a pharmacy themselves; the last admin cannot")
  void leaving() {
    assertThat(
            person
                .post(
                    "/api/v1/auth/pharmacies/" + own + "/leave",
                    "{\"password\":\"Admin#2026x-strong\"}")
                .expect(409)
                .string("$.reason"))
        .isEqualTo("last_admin");
    assertThat(
            person
                .post("/api/v1/auth/pharmacies/" + other + "/leave", "{\"password\":\"nope\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_password");
    person
        .post(
            "/api/v1/auth/pharmacies/" + other + "/leave", "{\"password\":\"Admin#2026x-strong\"}")
        .expect(200);
    person.in(other, null).get("/api/v1/products").expect(403);
    assertThat(
            otherAdmin
                .get("/api/v1/users?q=" + personEmail)
                .expect(200)
                .json()
                .<List<Boolean>>read("$.data.items[*].active"))
        .containsExactly(false);
    assertThat(otherAdmin.get("/api/v1/audit?action=MEMBER_LEFT").expect(200).body())
        .contains(membershipOwner());
    // A staff login is switched off by its pharmacy instead.
    Staff.Login login =
        Staff.login(otherAdmin, anonymous, "Baraka", "CASHIER", "Staff#2026x-strong");
    assertThat(
            anonymous
                .as(anonymous.signIn(login.signIn(), "Staff#2026x-strong"))
                .post(
                    "/api/v1/auth/pharmacies/" + other + "/leave",
                    "{\"password\":\"Staff#2026x-strong\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("managed_login");
  }

  private String membershipOwner() {
    return person.get("/api/v1/auth/me").expect(200).string("$.data.user.uid");
  }

  @Test
  @DisplayName("G15 the pharmacy keeps its own staff number, title and work contacts")
  void staffDetails() {
    otherAdmin
        .put(
            "/api/v1/users/" + membership,
            """
            {"roleUid":%s,"allBranches":true,"active":true,"staffNumber":"S-001",
             "jobTitle":"Dispenser","workPhone":"0754 000 222","workEmail":"neema@other.co.tz"}
            """
                .formatted(roleOf(otherAdmin, membership)))
        .expect(200);
    Api.Response found = otherAdmin.get("/api/v1/users?q=s-001").expect(200);
    assertThat(found.string("$.data.items[0].staff.staffNumber")).isEqualTo("S-001");
    assertThat(found.string("$.data.items[0].staff.workPhone")).isEqualTo("+255754000222");
    assertThat(found.string("$.data.items[0].staff.jobTitle")).isEqualTo("Dispenser");

    // Unique in the pharmacy, any case.
    Staff.Login login = Staff.login(otherAdmin, anonymous, "Asha", "CASHIER", "Staff#2026x-strong");
    assertThat(
            otherAdmin
                .put(
                    "/api/v1/users/" + login.membershipUid(),
                    """
                    {"roleUid":%s,"allBranches":true,"active":true,"staffNumber":"s-001"}
                    """
                        .formatted(roleOf(otherAdmin, login.membershipUid())))
                .expect(409)
                .string("$.reason"))
        .isEqualTo("duplicate_staff_number");

    // Her own pharmacy never sees what the other one keeps.
    assertThat(person.in(own, null).get("/api/v1/users").expect(200).body())
        .doesNotContain("S-001")
        .doesNotContain("Dispenser");
  }

  private static String roleOf(Api admin, String membershipUid) {
    List<String> roles =
        admin
            .get("/api/v1/users?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[?(@.membershipUid == '" + membershipUid + "')].roleUid");
    return "\"" + roles.getFirst() + "\"";
  }

  @Test
  @DisplayName("G16 a pharmacy that requires two-factor sends people without it to set it up")
  void requiredTwoFactor() {
    // The admin must have a second factor before requiring one of everyone.
    assertThat(
            otherAdmin
                .put("/api/v1/settings", "{\"requireTwoFactor\":true}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("two_factor_needed_first");
    enableTwoFactor(otherAdmin);
    otherAdmin.put("/api/v1/settings", "{\"requireTwoFactor\":true}").expect(200);
    assertThat(person.in(other, null).get("/api/v1/products").expect(403).string("$.reason"))
        .isEqualTo("two_factor_required_by_pharmacy");
    // Her own pharmacy does not require it; her account pages stay open to set it up.
    person.in(own, null).get("/api/v1/products").expect(200);
    enableTwoFactor(person);
    person.in(other, null).get("/api/v1/products").expect(200);
    otherAdmin.get("/api/v1/products").expect(200);
  }

  private static void enableTwoFactor(Api who) {
    String secret = who.post("/api/v1/auth/2fa/setup", "{}").expect(200).string("$.data.secret");
    who.post("/api/v1/auth/2fa/enable", "{\"code\":\"" + totp(secret, Instant.now()) + "\"}")
        .expect(200);
  }

  @Test
  @DisplayName("G17 a session keeps the strictest inactivity limit of the pharmacies it works in")
  void strictestIdleLimit() {
    person.in(own, null).put("/api/v1/settings", "{\"idleMinutes\":25}").expect(200);
    otherAdmin.put("/api/v1/settings", "{\"idleMinutes\":10}").expect(200);
    assertThat(activity(person.in(own, null))).isEqualTo("25");
    assertThat(activity(person.in(other, null))).isEqualTo("10");
    // Back in the laxer pharmacy, or on her own account pages: still 10, no flipping.
    assertThat(activity(person.in(own, null))).isEqualTo("10");
    assertThat(activity(person)).isEqualTo("10");
  }

  private static String activity(Api who) {
    return who.post("/api/v1/auth/activity", "{}").expect(200).string("$.data.idleMinutes");
  }

  private static String totp(String base32Secret, Instant at) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(base32(base32Secret), "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(at.getEpochSecond() / 30).array());
      int offset = hash[hash.length - 1] & 0xf;
      int binary = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
      return String.format("%06d", binary % 1_000_000);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] base32(String value) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    String clean = value.replace("=", "").replace(" ", "").toUpperCase();
    ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
    int buffer = 0;
    int bits = 0;
    for (char c : clean.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.put((byte) (buffer >> (bits - 8)));
        bits -= 8;
      }
    }
    return out.array();
  }
}
