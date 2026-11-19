package tz.co.pharmacy.modules.integrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

class WebhookPayloadTest {

  record SampleEvent(EventMetadata metadata, UUID saleUid, String number) implements DomainEvent {}

  @Test
  void theSignatureIsHmacSha256OfTheBodyInHex() {
    assertThat(WebhookSender.signature("key", "The quick brown fox jumps over the lazy dog"))
        .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
  }

  @Test
  void theCloudEventCarriesTheEventWithoutItsMetadata() {
    UUID eventId = UUID.randomUUID();
    UUID tenantUid = UUID.randomUUID();
    UUID saleUid = UUID.randomUUID();
    Instant at = Instant.parse("2026-11-02T09:15:00Z");
    EventMetadata metadata =
        new EventMetadata(eventId, "sale.completed", tenantUid, null, at, "a@b.c", "corr");

    String body =
        new WebhooksImpl(null, null, null, null, null, java.time.Clock.systemUTC())
            .cloudEvent(
                eventId,
                "sale.completed",
                at,
                tenantUid,
                new SampleEvent(metadata, saleUid, "INV-HQ-000001"));

    assertThat(body)
        .contains("\"specversion\":\"1.0\"")
        .contains("\"id\":\"" + eventId + "\"")
        .contains("\"source\":\"pharmacy-core/tenants/" + tenantUid + "\"")
        .contains("\"type\":\"sale.completed\"")
        .contains("\"time\":\"2026-11-02T09:15:00Z\"")
        .contains("\"saleUid\":\"" + saleUid + "\"")
        .contains("\"number\":\"INV-HQ-000001\"")
        .doesNotContain("metadata")
        .doesNotContain("a@b.c");
  }
}
