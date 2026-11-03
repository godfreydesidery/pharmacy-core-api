package tz.co.pharmacy.platform.persistence.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditValuesTest {

  @Test
  @DisplayName("secrets are masked whatever their case or position in the name")
  void masksSecrets() {
    for (String name :
        List.of(
            "passwordHash",
            "password",
            "totpSecret",
            "tokenHash",
            "recoveryCodeHash",
            "credentialId",
            "publicKeyCose",
            "apiSecret",
            "OTP_SEED")) {
      assertThat(AuditValues.isSecret(name)).as(name).isTrue();
      assertThat(AuditValues.value(name, "value")).as(name).isEqualTo(AuditValues.MASK);
    }
  }

  @Test
  @DisplayName("ordinary properties keep their value, made JSON-safe")
  void keepsOrdinaryValues() {
    UUID uid = UUID.randomUUID();
    Instant at = Instant.parse("2026-11-03T08:00:00Z");
    assertThat(AuditValues.value("name", "Paracetamol")).isEqualTo("Paracetamol");
    assertThat(AuditValues.value("quantity", 12)).isEqualTo(12);
    assertThat(AuditValues.value("active", true)).isEqualTo(true);
    assertThat(AuditValues.value("productUid", uid)).isEqualTo(uid.toString());
    assertThat(AuditValues.value("expiresAt", at)).isEqualTo("2026-11-03T08:00:00Z");
    assertThat(AuditValues.value("status", Thread.State.NEW)).isEqualTo("NEW");
    assertThat(AuditValues.value("lines", List.of(1, 2, 3))).isEqualTo("[3 items]");
    assertThat(AuditValues.value("name", null)).isNull();
    assertThat(AuditValues.value("password", null)).isNull();
  }

  @Test
  @DisplayName("an explicit event's maps are masked too")
  void masksMaps() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("email", "amina@afya.co.tz");
    values.put("newPassword", "hunter22");
    assertThat(AuditValues.masked(values))
        .containsEntry("email", "amina@afya.co.tz")
        .containsEntry("newPassword", AuditValues.MASK);
  }

  @Test
  @DisplayName("long texts are cut short")
  void truncatesLongText() {
    String longText = "x".repeat(800);
    assertThat((String) AuditValues.value("note", longText)).hasSize(501);
  }
}
