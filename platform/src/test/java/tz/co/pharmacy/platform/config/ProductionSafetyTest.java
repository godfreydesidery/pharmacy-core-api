package tz.co.pharmacy.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyTest {

  private static final String KEY = "k".repeat(ProductionSafety.MIN_SECRET_LENGTH);

  static MockEnvironment server() {
    return new MockEnvironment()
        .withProperty("pharmacy.access.encryption-key", KEY)
        .withProperty("pharmacy.files.signing-secret", KEY)
        .withProperty("pharmacy.files.local.root", "/data/files")
        .withProperty("pharmacy.access.web-url", "https://app.example.co.tz");
  }

  @Test
  @DisplayName("a server with real secrets, an absolute file root and a public web URL starts")
  void configuredServerStarts() {
    assertThatCode(() -> ProductionSafety.require(server())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a server on the published defaults refuses to start, naming every variable")
  void defaultsRefuse() {
    assertThatThrownBy(() -> ProductionSafety.require(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PHARMACY_ACCESS_ENCRYPTIONKEY")
        .hasMessageContaining("PHARMACY_FILES_SIGNING_SECRET")
        .hasMessageContaining("PHARMACY_FILES_ROOT")
        .hasMessageContaining("PHARMACY_ACCESS_WEBURL");
  }

  @Test
  @DisplayName("the development values, short secrets and a localhost web URL are each refused")
  void eachRuleRefuses() {
    assertThat(
            ProductionSafety.problems(
                server()
                    .withProperty(
                        "pharmacy.access.encryption-key", ProductionSafety.LOCAL_ENCRYPTION_KEY)))
        .singleElement()
        .asString()
        .contains("encryption-key");
    assertThat(
            ProductionSafety.problems(
                server().withProperty("pharmacy.files.signing-secret", "short")))
        .singleElement()
        .asString()
        .contains("shorter than");
    assertThat(
            ProductionSafety.problems(
                server().withProperty("pharmacy.access.web-url", "http://localhost:4300")))
        .singleElement()
        .asString()
        .contains("web-url");
    assertThat(
            ProductionSafety.problems(
                server().withProperty("pharmacy.files.local.root", "target/files")))
        .singleElement()
        .asString()
        .contains("relative");
  }

  @Test
  @DisplayName("with S3 storage the root is not needed, but the bucket and the signing secret are")
  void s3NeedsABucketAndTheSigningSecretButNoRoot() {
    MockEnvironment s3 =
        new MockEnvironment()
            .withProperty("pharmacy.access.encryption-key", KEY)
            .withProperty("pharmacy.access.web-url", "https://app.example.co.tz")
            .withProperty("pharmacy.files.storage", "s3");
    assertThat(ProductionSafety.problems(s3))
        .hasSize(2)
        .anySatisfy(problem -> assertThat(problem).contains("PHARMACY_FILES_S3_BUCKET"))
        .anySatisfy(problem -> assertThat(problem).contains("PHARMACY_FILES_SIGNING_SECRET"));
    s3.withProperty("pharmacy.files.s3.bucket", "pharmacy-files")
        .withProperty("pharmacy.files.signing-secret", KEY);
    assertThat(ProductionSafety.problems(s3)).isEmpty();
  }

  @Test
  @DisplayName("the local and test profiles keep their development defaults")
  void developmentProfilesAreExempt() {
    MockEnvironment local = new MockEnvironment();
    local.setActiveProfiles("local", "sim");
    assertThatCode(() -> ProductionSafety.require(local)).doesNotThrowAnyException();
    MockEnvironment test = new MockEnvironment();
    test.setActiveProfiles("test");
    assertThatCode(() -> ProductionSafety.require(test)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("every key of the keyring is checked: length, published default, duplicates, ids")
  void keyringKeysAreChecked() {
    String other = "o".repeat(ProductionSafety.MIN_SECRET_LENGTH);
    assertThat(
            ProductionSafety.problems(
                server()
                    .withProperty("pharmacy.keys.k2026t", other)
                    .withProperty("pharmacy.sealing.totp", "k2026t")))
        .isEmpty();
    assertThat(ProductionSafety.problems(server().withProperty("pharmacy.keys.k2026t", "short")))
        .singleElement()
        .asString()
        .contains("PHARMACY_KEYS_K2026T", "shorter than");
    assertThat(ProductionSafety.problems(server().withProperty("pharmacy.keys.k2026t", KEY)))
        .singleElement()
        .asString()
        .contains("same value");
    assertThat(
            ProductionSafety.problems(
                server()
                    .withProperty("pharmacy.keys.k2026t", ProductionSafety.LOCAL_ENCRYPTION_KEY)))
        .singleElement()
        .asString()
        .contains("published default");
    assertThat(ProductionSafety.problems(server().withProperty("pharmacy.sealing.webhooks", "k9")))
        .singleElement()
        .asString()
        .contains("PHARMACY_SEALING_WEBHOOKS", "not in the keyring");
  }

  @Test
  @DisplayName("the legacy key may go once every purpose seals with a key of its own")
  void legacyKeyMayGoAfterRotation() {
    MockEnvironment rotated =
        new MockEnvironment()
            .withProperty("pharmacy.keys.k2026t", "t".repeat(40))
            .withProperty("pharmacy.keys.k2026p", "p".repeat(40))
            .withProperty("pharmacy.keys.k2026w", "w".repeat(40))
            .withProperty("pharmacy.sealing.totp", "k2026t")
            .withProperty("pharmacy.sealing.providers", "k2026p")
            .withProperty("pharmacy.access.web-url", "https://app.example.co.tz")
            .withProperty("pharmacy.files.storage", "s3")
            .withProperty("pharmacy.files.s3.bucket", "pharmacy-prod-files")
            // Upload links are signed with either store (Batch 6, files on S3).
            .withProperty("pharmacy.files.signing-secret", "s".repeat(40));
    assertThat(ProductionSafety.problems(rotated))
        .singleElement()
        .asString()
        .contains("PHARMACY_ACCESS_ENCRYPTIONKEY");
    assertThat(
            ProductionSafety.problems(rotated.withProperty("pharmacy.sealing.webhooks", "k2026w")))
        .isEmpty();
  }

  @Test
  @DisplayName("migrating at start (the owner password in the API) is a warning, not a refusal")
  void migrateAndRunWarns() {
    assertThat(ProductionSafety.warnings(server()))
        .singleElement()
        .asString()
        .contains("migrate-and-run");
    assertThatCode(() -> ProductionSafety.require(server())).doesNotThrowAnyException();
    assertThat(
            ProductionSafety.warnings(
                server().withProperty("pharmacy.migrations.mode", "run-only")))
        .isEmpty();
    assertThat(
            ProductionSafety.warnings(
                server()
                    .withProperty("pharmacy.migrations.mode", "run-only")
                    .withProperty("pharmacy.database.owner-password", "secret")))
        .singleElement()
        .asString()
        .contains("PHARMACY_DB_OWNER_PASSWORD");
    assertThat(
            ProductionSafety.warnings(
                server().withProperty("pharmacy.database.migrations-enabled", "false")))
        .isEmpty();
  }
}
