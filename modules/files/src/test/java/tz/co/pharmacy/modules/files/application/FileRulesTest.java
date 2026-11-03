package tz.co.pharmacy.modules.files.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.files.ports.inbound.FileRules;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.RefusalKind;

class FileRulesTest {

  @Test
  void acceptsImagesAndPdfOnly() {
    assertThat(FileRules.contentType("image/PNG")).isEqualTo("image/png");
    assertThat(FileRules.contentType("image/jpg")).isEqualTo("image/jpeg");
    assertThat(FileRules.contentType("application/pdf; charset=binary"))
        .isEqualTo("application/pdf");
    assertThatThrownBy(() -> FileRules.contentType("text/html"))
        .isInstanceOfSatisfying(
            Refusal.class,
            refusal -> assertThat(refusal.kind()).isEqualTo(RefusalKind.UNSUPPORTED_MEDIA_TYPE));
  }

  @Test
  void refusesEmptyAndOversizedFiles() {
    assertThat(FileRules.size(FileRules.MAX_SIZE)).isEqualTo(FileRules.MAX_SIZE);
    assertThatThrownBy(() -> FileRules.size(0)).isInstanceOf(Refusal.class);
    assertThatThrownBy(() -> FileRules.size(FileRules.MAX_SIZE + 1))
        .isInstanceOfSatisfying(
            Refusal.class,
            refusal -> {
              assertThat(refusal.kind()).isEqualTo(RefusalKind.PAYLOAD_TOO_LARGE);
              assertThat(refusal.reason()).isEqualTo("file_too_large");
            });
  }

  @Test
  void keysNameTheTenantAndMonth() {
    UUID tenant = UUID.fromString("0193f1c2-0000-7000-8000-000000000001");
    UUID file = UUID.fromString("0193f1c2-0000-7000-8000-000000000002");
    assertThat(FileRules.key(tenant, Instant.parse("2026-03-09T23:59:00Z"), file))
        .isEqualTo("tenants/" + tenant + "/2026/03/" + file);
  }

  @Test
  void namesLoseTheirPath() {
    assertThat(FileRules.name("C:\\scans\\rx 001.png")).isEqualTo("rx 001.png");
    assertThat(FileRules.name("../../invoice.pdf")).isEqualTo("invoice.pdf");
  }

  @Test
  void ownerTypesAndPurposesAreClosedLists() {
    assertThat(FileRules.ownerType("sale")).isEqualTo("SALE");
    assertThat(FileRules.purpose(null)).isEqualTo("ATTACHMENT");
    assertThatThrownBy(() -> FileRules.ownerType("PLANET")).isInstanceOf(Refusal.class);
  }
}
