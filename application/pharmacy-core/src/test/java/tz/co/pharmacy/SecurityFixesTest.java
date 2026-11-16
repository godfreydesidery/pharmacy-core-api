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
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * The security fixes from the QA report (gaps.md): webhook targets (H-01), root access in the
 * tenant's audit trail (M-13), admin unlock (M-14), single-use authenticator codes (M-15),
 * invitations instead of silent membership (M-16) and signing out of other devices (L-05).
 */
class SecurityFixesTest extends IntegrationTest {

  private Api anonymous;
  private Api root;
  private Api admin;
  private String tenant;
  private String adminEmail;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SF" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    adminEmail = code.toLowerCase() + "@test.local";
    tenant = register(code, adminEmail);
    admin = anonymous.as(anonymous.signIn(adminEmail, "Admin#2026x")).in(tenant, null);
  }

  private String register(String code, String email) {
    return root.post(
            "/api/v1/platform/tenants",
            """
            {"code":"%s","name":"Security %s","legalName":"Security %s Ltd","country":"TZ",
             "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
             "headOffice":{"code":"HQ","name":"Head Office"},
             "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
            """
                .formatted(code, code, code, email))
        .expect(201)
        .string("$.data.uid");
  }

  private static String cashierRole(Api as) {
    List<String> uids =
        as.get("/api/v1/roles").expect(200).json().read("$.data[?(@.code == 'CASHIER')].uid");
    return uids.getFirst();
  }

  private Response addUser(Api as, String email, String password) {
    return as.post(
        "/api/v1/users",
        """
        {"name":"Staff","email":"%s","password":"%s","roleUid":"%s","allBranches":true}
        """
            .formatted(email, password, cashierRole(as)));
  }

  @Test
  @DisplayName("H-01 webhooks refuse loopback, private, link-local and internal targets")
  void webhookTargetsMustBePublic() {
    for (String url :
        List.of(
            "http://localhost:8080/actuator",
            "https://127.0.0.1/x",
            "https://10.0.0.5/x",
            "https://192.168.1.20/x",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/x",
            "https://db.internal/x",
            "https://printer.local/x")) {
      Response refused =
          admin.post(
              "/api/v1/webhooks",
              """
              {"name":"SSRF","url":"%s","eventTypes":["sale.completed"],"active":true}
              """
                  .formatted(url));
      assertThat(refused.status()).as(url).isEqualTo(422);
      assertThat(refused.string("$.reason")).as(url).isEqualTo("webhook_url_not_allowed");
      assertThat(refused.string("$.field")).isEqualTo("url");
      assertThat(refused.string("$.detail")).as(url).startsWith("The address ");
    }
  }

  @Test
  @DisplayName("M-13 a root administrator's visit and changes show in the tenant's audit trail")
  void rootAccessIsAudited() {
    Api rootInTenant = root.in(tenant, null);
    rootInTenant.get("/api/v1/customers").expect(200);
    rootInTenant
        .post("/api/v1/customers", "{\"name\":\"Root Made\",\"phone\":\"+255700000009\"}")
        .expect(201);
    Api.eventually(
        () -> {
          String body =
              admin.get("/api/v1/audit?actor=root@test.local&limit=50").expect(200).body();
          return body.contains("ROOT_ACTED_IN_TENANT") && body.contains("\"CREATE\"");
        });
  }

  @Test
  @DisplayName("M-14 an administrator sees a locked member and unlocks them")
  void adminUnlocksALockedMember() {
    String email = "locked." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    String membership =
        addUser(admin, email, "Staff#2026x").expect(201).string("$.data.membershipUid");
    Response last = null;
    for (int i = 0; i < 6; i++) {
      last =
          anonymous.post(
              "/api/v1/auth/sign-in", "{\"email\":\"" + email + "\",\"password\":\"Wrong#2026x\"}");
    }
    assertThat(last.status()).isEqualTo(423);
    List<String> locked =
        admin.get("/api/v1/users/locked").expect(200).json().read("$.data[*].membershipUid");
    assertThat(locked).contains(membership);

    admin.post("/api/v1/users/" + membership + "/unlock", "{}").expect(200);
    assertThat(admin.get("/api/v1/users/locked").expect(200).body()).doesNotContain(membership);
    anonymous.signIn(email, "Staff#2026x");
  }

  @Test
  @DisplayName("M-15 an authenticator code works once")
  void authenticatorCodesAreSingleUse() {
    String email = "totp." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
    addUser(admin, email, "Staff#2026x").expect(201);
    Api staff = anonymous.as(anonymous.signIn(email, "Staff#2026x"));
    String secret = staff.post("/api/v1/auth/2fa/setup", "{}").expect(200).string("$.data.secret");
    String now = totp(secret, Instant.now());
    staff.post("/api/v1/auth/2fa/enable", "{\"code\":\"" + now + "\"}").expect(200);

    // The enabling code cannot sign in again.
    Response replay = verify(email, now);
    assertThat(replay.status()).isEqualTo(401);
    assertThat(replay.string("$.reason")).isEqualTo("code_already_used");

    // The next step's code works once.
    String next = totp(secret, Instant.now().plusSeconds(30));
    verify(email, next).expect(201);
    Response again = verify(email, next);
    assertThat(again.string("$.reason")).isEqualTo("code_already_used");
  }

  private Response verify(String email, String code) {
    Response challenge =
        anonymous
            .post(
                "/api/v1/auth/sign-in",
                "{\"email\":\"" + email + "\",\"password\":\"Staff#2026x\"}")
            .expect(202);
    return anonymous.post(
        "/api/v1/auth/sign-in/verify",
        "{\"challengeToken\":\"%s\",\"code\":\"%s\"}"
            .formatted(challenge.string("$.data.challengeToken"), code));
  }

  @Test
  @DisplayName("M-16 adding an account that exists elsewhere sends an invitation instead")
  void existingAccountsAreInvited() {
    String other = "SO" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String otherEmail = other.toLowerCase() + "@test.local";
    String otherTenant = register(other, otherEmail);
    Api otherAdmin =
        anonymous.as(anonymous.signIn(otherEmail, "Admin#2026x")).in(otherTenant, null);

    Response invited = addUser(otherAdmin, adminEmail, "Ignored#2026x");
    assertThat(invited.status()).isEqualTo(202);
    assertThat(invited.read("$.data.invited", Boolean.class)).isTrue();
    List<String> tenants =
        admin.get("/api/v1/auth/me").expect(200).json().read("$.data.memberships[*].tenantUid");
    assertThat(tenants).doesNotContain(otherTenant);
    assertThat(otherAdmin.get("/api/v1/users/invitations").expect(200).body()).contains(adminEmail);
    // The account's password is untouched.
    anonymous.signIn(adminEmail, "Admin#2026x");
  }

  @Test
  @DisplayName("L-05 signing out of every other device keeps this one")
  void signOutOfOtherDevices() {
    Api first = anonymous.as(anonymous.signIn(adminEmail, "Admin#2026x"));
    Api second = anonymous.as(anonymous.signIn(adminEmail, "Admin#2026x"));
    Response done = first.post("/api/v1/auth/sessions/revoke-others", "{}").expect(200);
    assertThat(done.read("$.data.revoked", Integer.class)).isGreaterThanOrEqualTo(2);
    first.get("/api/v1/auth/me").expect(200);
    assertThat(second.get("/api/v1/auth/me").status()).isEqualTo(401);
    List<Object> live = first.get("/api/v1/auth/sessions").expect(200).json().read("$.data[*]");
    assertThat(live).hasSize(1);
  }

  /** RFC 6238 TOTP: HMAC-SHA1, 30-second steps, 6 digits. */
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
