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
  @DisplayName("with S3 storage the local file secret and root are not needed")
  void s3NeedsNoLocalFileSettings() {
    MockEnvironment s3 =
        new MockEnvironment()
            .withProperty("pharmacy.access.encryption-key", KEY)
            .withProperty("pharmacy.access.web-url", "https://app.example.co.tz")
            .withProperty("pharmacy.files.storage", "s3");
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
}
