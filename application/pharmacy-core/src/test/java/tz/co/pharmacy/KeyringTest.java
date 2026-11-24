package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import tz.co.pharmacy.platform.crypto.Keyring;
import tz.co.pharmacy.platform.crypto.KeyringSettings;
import tz.co.pharmacy.platform.crypto.Purpose;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Key rotation (OPS-5). This context has rotated: every purpose seals with its own new key (the
 * keys themselves are in every test context, from application-test.yml), while the values the rest
 * of the suite sealed are still under the legacy key. Old values still open, new ones name their
 * key and purpose, and the re-seal moves every value to its purpose's key until the legacy key is
 * no longer used by any.
 */
@TestPropertySource(
    properties = {
      // Kept, as an operator keeps it until the status shows it removable (unset, the keyring
      // drops the legacy key once every purpose has its own).
      "pharmacy.access.encryption-key=" + KeyringSettings.LOCAL_KEY,
      "pharmacy.sealing.totp=testtotp",
      "pharmacy.sealing.providers=testproviders",
      "pharmacy.sealing.webhooks=testwebhooks"
    })
class KeyringTest extends IntegrationTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired Keyring keyring;

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api anonymous;
  private Api root;
  private Api self;
  private Api admin;
  private String tenant;
  private String email;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "KR" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    email = "kr." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Keys %s","legalName":"Keys %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Neema Joseph","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    self = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong"));
    admin = self.in(tenant, null);
  }

  @Test
  @DisplayName("an unversioned value opens with the legacy key; a new one names its key")
  void legacyAndVersionedValues() {
    byte[] seed = "a TOTP seed".getBytes(StandardCharsets.UTF_8);
    String legacy = legacySeal(seed);
    assertThat(Keyring.keyIdOf(legacy)).isEqualTo(KeyringSettings.LEGACY);
    assertThat(keyring.open(Purpose.TOTP, legacy)).isEqualTo(seed);

    String sealed = keyring.seal(Purpose.TOTP, seed);
    assertThat(sealed).startsWith("v2:testtotp:");
    assertThat(Keyring.keyIdOf(sealed)).isEqualTo("testtotp");
    assertThat(keyring.open(Purpose.TOTP, sealed)).isEqualTo(seed);
    assertThat(keyring.seal(Purpose.TOTP, seed)).as("a fresh IV each time").isNotEqualTo(sealed);

    // While a purpose is on the legacy key it keeps writing the unversioned format, which the
    // previous release reads during a rolling deploy.
    Keyring unrotated = new Keyring(new KeyringSettings("", Map.of(), Map.of()));
    String old = unrotated.seal(Purpose.TOTP, seed);
    assertThat(old).doesNotContain(":");
    assertThat(keyring.open(Purpose.TOTP, old)).isEqualTo(seed);
  }

  @Test
  @DisplayName("each purpose has its own key, and a value does not open as another purpose")
  void purposesAreSeparate() {
    assertThat(keyring.seal(Purpose.PROVIDERS, "x")).startsWith("v2:testproviders:");
    assertThat(keyring.seal(Purpose.WEBHOOKS, "x")).startsWith("v2:testwebhooks:");
    String totp = keyring.seal(Purpose.TOTP, "seed");
    assertThatThrownBy(() -> keyring.open(Purpose.WEBHOOKS, totp))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> keyring.open(Purpose.TOTP, "v2:gone:AAAAAAAAAAAAAAAAAAAAAAAA"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not in the keyring");
    // A keyring without the key cannot open what it sealed: the key stays until nothing names it.
    Keyring without =
        new Keyring(
            new KeyringSettings(
                KeyringSettings.LOCAL_KEY, Map.of("testother", "x".repeat(40)), Map.of()));
    assertThatThrownBy(() -> without.open(Purpose.TOTP, totp))
        .isInstanceOf(IllegalStateException.class);
    // An active key that is not configured stops the start.
    assertThatThrownBy(
            () -> new Keyring(new KeyringSettings("", Map.of(), Map.of(Purpose.TOTP, "nokey"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PHARMACY_SEALING_TOTP");
  }

  @Test
  @DisplayName(
      "the re-seal moves every value to its purpose's key, the status counts them per key, and"
          + " the old key is then removable")
  void resealMovesEveryValue() throws SQLException {
    // A TOTP seed, sealed by this context with the new key, put back as it was before the rotation.
    String secret = self.post("/api/v1/auth/2fa/setup", "{}").expect(200).string("$.data.secret");
    self.post("/api/v1/auth/2fa/enable", "{\"code\":\"" + totp(secret, Instant.now()) + "\"}")
        .expect(200);
    byte[] seed = base32(secret);
    String sealedSeed = userTotp();
    assertThat(sealedSeed).startsWith("v2:testtotp:");
    assertThat(keyring.open(Purpose.TOTP, sealedSeed)).isEqualTo(seed);
    ownerUpdate("update access.users set totp_secret = ? where email = ?", legacySeal(seed), email);

    // A webhook secret sealed before the rotation, and a previous one still in plain text.
    Response webhook =
        admin
            .post(
                "/api/v1/webhooks",
                """
                {"name":"Keys","url":"http://127.0.0.2:9/hook","eventTypes":["product.changed"],
                 "active":false}
                """)
            .expect(201);
    String hook = webhook.string("$.data.uid");
    String signing = webhook.string("$.data.secret");
    assertThat(webhookColumn(hook, "secret")).startsWith("v2:testwebhooks:");
    String previous = "whsec_" + Base64.getEncoder().encodeToString(random(32));
    tenantUpdate(
        "update integrations.webhooks set secret = ?, previous_secret = ? where uid = ?::uuid",
        legacySeal(signing.getBytes(StandardCharsets.UTF_8)),
        previous,
        hook);

    // A pharmacy's own SMS provider, its secrets sealed before the rotation.
    String providerJson = "{\"apiKey\":\"at-" + UUID.randomUUID() + "\"}";
    String provider = UUID.randomUUID().toString();
    tenantUpdate(
        "insert into notifications.provider_settings (uid, tenant_uid, channel, mode, provider,"
            + " name, settings, secrets, active, created_at, created_by)"
            + " values (?::uuid, ?::uuid, 'SMS', 'OWN', 'africastalking', 'Old key', '{}', ?,"
            + " false, now(), 'test')",
        provider,
        tenant,
        legacySeal(providerJson.getBytes(StandardCharsets.UTF_8)));

    // The running application still reads the old seed: the next step's code signs in.
    Response challenge =
        anonymous
            .post(
                "/api/v1/auth/sign-in",
                "{\"email\":\"" + email + "\",\"password\":\"Admin#2026x-strong\"}")
            .expect(202);
    anonymous
        .post(
            "/api/v1/auth/sign-in/verify",
            "{\"challengeToken\":\"%s\",\"code\":\"%s\"}"
                .formatted(
                    challenge.string("$.data.challengeToken"),
                    totp(secret, Instant.now().plusSeconds(30))))
        .expect(201);

    Response before = root.get("/api/v1/platform/keys").expect(200);
    assertThat(count(before, "access.users.totp_secret", "legacy")).isPositive();
    assertThat(count(before, "integrations.webhooks.secret", "legacy")).isPositive();
    assertThat(count(before, "integrations.webhooks.previous_secret", "plain")).isPositive();
    assertThat(count(before, "notifications.provider_settings.secrets", "legacy")).isPositive();
    assertThat(before.read("$.data.toReseal", Long.class)).isGreaterThanOrEqualTo(4);
    assertThat(before.read("$.data.active.totp", String.class)).isEqualTo("testtotp");
    assertThat(removable(before, "legacy")).isFalse();

    root.post("/api/v1/platform/keys/reseal", "{}").expect(202);
    Response after = waitForRun();
    assertThat(after.string("$.data.lastRun.outcome")).isEqualTo("completed");

    // Every value is under its purpose's new key and opens to what it was.
    String resealedSeed = userTotp();
    assertThat(resealedSeed).startsWith("v2:testtotp:");
    assertThat(keyring.open(Purpose.TOTP, resealedSeed)).isEqualTo(seed);
    assertThat(webhookColumn(hook, "secret")).startsWith("v2:testwebhooks:");
    assertThat(keyring.openText(Purpose.WEBHOOKS, webhookColumn(hook, "secret")))
        .isEqualTo(signing);
    assertThat(keyring.openText(Purpose.WEBHOOKS, webhookColumn(hook, "previous_secret")))
        .isEqualTo(previous);
    String providerSecrets = providerSecrets(provider);
    assertThat(providerSecrets).startsWith("v2:testproviders:");
    assertThat(keyring.openText(Purpose.PROVIDERS, providerSecrets)).isEqualTo(providerJson);

    // Nothing names the legacy key any more (the whole suite's values included): it may go.
    assertThat(keysOf(after, "access.users.totp_secret")).containsOnly("testtotp");
    assertThat(keysOf(after, "integrations.webhooks.secret")).containsOnly("testwebhooks");
    assertThat(keysOf(after, "integrations.webhooks.previous_secret")).containsOnly("testwebhooks");
    assertThat(keysOf(after, "notifications.provider_settings.secrets"))
        .containsOnly("testproviders");
    assertThat(after.read("$.data.toReseal", Long.class)).isZero();
    assertThat(removable(after, "legacy")).isTrue();
    assertThat(removable(after, "testtotp")).isFalse();

    // A second run finds nothing to do.
    root.post("/api/v1/platform/keys/reseal", "{}").expect(202);
    assertThat(waitForRun().read("$.data.lastRun.resealed", Long.class)).isZero();
  }

  @Test
  @DisplayName("only a root administrator sees the keys and starts the re-seal")
  void rootOnly() {
    assertThat(self.get("/api/v1/platform/keys").status()).isEqualTo(403);
    assertThat(self.post("/api/v1/platform/keys/reseal", "{}").status()).isEqualTo(403);
    assertThat(anonymous.get("/api/v1/platform/keys").status()).isEqualTo(401);
  }

  private Response waitForRun() {
    long deadline = System.nanoTime() + 180_000_000_000L;
    while (System.nanoTime() < deadline) {
      Response status = root.get("/api/v1/platform/keys").expect(200);
      String outcome = status.json().read("$.data.lastRun.outcome");
      if (outcome != null && !"running".equals(outcome)) {
        return status;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("the re-seal did not finish within 3 minutes");
  }

  private static long count(Response status, String column, String key) {
    List<Number> found =
        status.json().read("$.data.columns[?(@.column == '" + column + "')].byKey." + key);
    return found.isEmpty() ? 0 : found.getFirst().longValue();
  }

  private static java.util.Set<String> keysOf(Response status, String column) {
    List<Map<String, Object>> found =
        status.json().read("$.data.columns[?(@.column == '" + column + "')].byKey");
    return found.getFirst().keySet();
  }

  private static boolean removable(Response status, String key) {
    List<Boolean> found = status.json().read("$.data.keys[?(@.id == '" + key + "')].removable");
    return found.getFirst();
  }

  private Connection ownerConnection() throws SQLException {
    return DriverManager.getConnection(url, owner, ownerPassword);
  }

  private String userTotp() throws SQLException {
    try (Connection connection = ownerConnection();
        PreparedStatement statement =
            connection.prepareStatement("select totp_secret from access.users where email = ?")) {
      statement.setString(1, email);
      return single(statement);
    }
  }

  private String webhookColumn(String hook, String column) throws SQLException {
    try (Connection connection = ownerConnection()) {
      asTenant(connection);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "select " + column + " from integrations.webhooks where uid = ?::uuid")) {
        statement.setString(1, hook);
        return single(statement);
      }
    }
  }

  private String providerSecrets(String provider) throws SQLException {
    try (Connection connection = ownerConnection()) {
      asTenant(connection);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "select secrets from notifications.provider_settings where uid = ?::uuid")) {
        statement.setString(1, provider);
        return single(statement);
      }
    }
  }

  private static String single(PreparedStatement statement) throws SQLException {
    try (ResultSet row = statement.executeQuery()) {
      assertThat(row.next()).isTrue();
      return row.getString(1);
    }
  }

  private void ownerUpdate(String sql, Object... values) throws SQLException {
    try (Connection connection = ownerConnection()) {
      execute(connection, sql, values);
    }
  }

  /** As the owner, under the pharmacy's row-level security (forced on the owner too). */
  private void tenantUpdate(String sql, Object... values) throws SQLException {
    try (Connection connection = ownerConnection()) {
      asTenant(connection);
      execute(connection, sql, values);
    }
  }

  private void asTenant(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("select set_config('pharmacy.tenant_uid', ?, false)")) {
      statement.setString(1, tenant);
      statement.execute();
    }
  }

  private static void execute(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) {
        statement.setObject(i + 1, values[i]);
      }
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  /** A value as the release before the keyring sealed it: unversioned, under the legacy key. */
  private static String legacySeal(byte[] plain) {
    try {
      byte[] iv = random(12);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(
              MessageDigest.getInstance("SHA-256")
                  .digest(KeyringSettings.LOCAL_KEY.getBytes(StandardCharsets.UTF_8)),
              "AES"),
          new GCMParameterSpec(128, iv));
      byte[] sealed = cipher.doFinal(plain);
      return Base64.getEncoder()
          .encodeToString(ByteBuffer.allocate(12 + sealed.length).put(iv).put(sealed).array());
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] random(int size) {
    byte[] bytes = new byte[size];
    RANDOM.nextBytes(bytes);
    return bytes;
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
