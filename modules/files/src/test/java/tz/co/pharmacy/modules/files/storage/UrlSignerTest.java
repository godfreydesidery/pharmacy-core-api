package tz.co.pharmacy.modules.files.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tz.co.pharmacy.platform.kernel.Refusal;

class UrlSignerTest {

  private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
  private final UrlSigner signer =
      new UrlSigner("a-test-secret-of-length", Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aSignedUrlVerifies() {
    long expires = signer.expiresIn(Duration.ofMinutes(5));
    String signature = signer.sign("PUT", "tenants/t/2026/11/f", expires, "image/png");
    assertThatCode(
            () -> signer.verify("PUT", "tenants/t/2026/11/f", expires, "image/png", signature))
        .doesNotThrowAnyException();
  }

  @Test
  void aSignatureIsBoundToMethodKeyAndType() {
    long expires = signer.expiresIn(Duration.ofMinutes(5));
    String signature = signer.sign("PUT", "tenants/t/2026/11/f", expires, "image/png");
    assertRefused(
        () -> signer.verify("GET", "tenants/t/2026/11/f", expires, "image/png", signature),
        "invalid_signature");
    assertRefused(
        () -> signer.verify("PUT", "tenants/t/2026/11/g", expires, "image/png", signature),
        "invalid_signature");
    assertRefused(
        () -> signer.verify("PUT", "tenants/t/2026/11/f", expires, "application/pdf", signature),
        "invalid_signature");
    assertRefused(
        () -> signer.verify("PUT", "tenants/t/2026/11/f", expires + 1, "image/png", signature),
        "invalid_signature");
  }

  @Test
  void anExpiredUrlIsRefused() {
    long expired = NOW.minusSeconds(1).getEpochSecond();
    String signature = signer.sign("GET", "k", expired, "image/png");
    assertRefused(() -> signer.verify("GET", "k", expired, "image/png", signature), "url_expired");
  }

  @Test
  void localUrlsPointAtTheSignedBlobRoute(@TempDir Path root) {
    LocalObjectStorage storage = new LocalObjectStorage(root, signer);
    String url = storage.presignGet("tenants/t/2026/11/f", "image/png", Duration.ofMinutes(5));
    assertThat(url)
        .startsWith("/api/v1/files/blob/tenants/t/2026/11/f?exp=")
        .contains("&ct=image%2Fpng&sig=");
  }

  @Test
  void aKeyCannotLeaveTheRoot(@TempDir Path root) {
    LocalObjectStorage storage = new LocalObjectStorage(root, signer);
    assertThatThrownBy(() -> storage.path("../etc/passwd")).isInstanceOf(Refusal.class);
    assertThatThrownBy(() -> storage.path("/etc/passwd")).isInstanceOf(Refusal.class);
  }

  private static void assertRefused(Runnable work, String reason) {
    assertThatThrownBy(work::run)
        .isInstanceOf(Refusal.class)
        .extracting(thrown -> ((Refusal) thrown).reason())
        .isEqualTo(reason);
  }
}
