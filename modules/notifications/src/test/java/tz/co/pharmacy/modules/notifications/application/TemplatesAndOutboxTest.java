package tz.co.pharmacy.modules.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;

class TemplatesAndOutboxTest {

  @Test
  @DisplayName("placeholders are filled, unknown ones render empty")
  void fillsPlaceholders() {
    assertThat(
            TemplateRenderer.text(
                "{{productName}} has {{ quantity }} left{{missing}}.",
                Map.of("productName", "Paracetamol", "quantity", 18)))
        .isEqualTo("Paracetamol has 18 left.");
  }

  @Test
  @DisplayName("email escapes the values and the template, and keeps line breaks")
  void emailEscapes() {
    String html =
        TemplateRenderer.email(
            "Hi <b>",
            "Hello {{name}} & co\nLine two",
            Map.of("name", "<script>alert(1)</script>", "link", "https://pharmacy.example/x"));
    assertThat(html)
        .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
        .contains("&amp; co<br>Line two")
        .contains("Hi &lt;b&gt;")
        .contains("href=\"https://pharmacy.example/x\"")
        .doesNotContain("<script>");
  }

  @Test
  @DisplayName("a relative link gets no email button")
  void relativeLinkHasNoButton() {
    assertThat(TemplateRenderer.email("s", "b", Map.of("link", "/inventory/stock")))
        .doesNotContain("Open Pharmacy Core");
  }

  @Test
  @DisplayName("every key has English and Swahili wording on every channel, filled by its sample")
  void catalogIsComplete() {
    for (String key : TemplateKeys.ALL) {
      assertThat(TemplateCatalog.known(key)).as(key).isTrue();
      for (Channel channel : Channel.values()) {
        for (String locale : TemplateCatalog.LOCALES) {
          TemplateCatalog.Default template = TemplateCatalog.forChannel(key, channel, locale);
          assertThat(template.body()).as(key + " " + channel + " " + locale).isNotBlank();
          TemplateResolver.Rendered rendered =
              TemplateResolver.render(
                  template.subject(), template.body(), channel, TemplateCatalog.sample(key));
          assertThat(rendered.text()).as(key + " " + channel + " " + locale).doesNotContain("{{");
          if (channel == Channel.EMAIL) {
            assertThat(rendered.html()).isNotBlank();
            assertThat(rendered.subject()).doesNotContain("{{");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("Swahili is chosen for sw and sw-TZ, English otherwise")
  void locales() {
    assertThat(TemplateCatalog.locale("sw-TZ")).isEqualTo("sw");
    assertThat(TemplateCatalog.locale("SW")).isEqualTo("sw");
    assertThat(TemplateCatalog.locale("en-GB")).isEqualTo("en");
    assertThat(TemplateCatalog.locale(null)).isEqualTo("en");
    assertThat(TemplateCatalog.forChannel(TemplateKeys.STOCK_LOW, Channel.SMS, "sw").body())
        .contains("inakaribia kuisha");
  }

  @Test
  @DisplayName("retries back off 1, 5, 15, then 60 minutes")
  void backoff() {
    assertThat(Backoff.after(1)).isEqualTo(Duration.ofMinutes(1));
    assertThat(Backoff.after(2)).isEqualTo(Duration.ofMinutes(5));
    assertThat(Backoff.after(3)).isEqualTo(Duration.ofMinutes(15));
    assertThat(Backoff.after(4)).isEqualTo(Duration.ofMinutes(60));
    assertThat(Backoff.after(9)).isEqualTo(Duration.ofMinutes(60));
  }

  @Test
  @DisplayName("phone numbers become E.164, local ones with the default country code")
  void phones() {
    assertThat(Phones.e164("0712 345 678", "255")).isEqualTo("+255712345678");
    assertThat(Phones.e164("+255-712-345-678", "255")).isEqualTo("+255712345678");
    assertThat(Phones.e164("00254 711 000 111", "255")).isEqualTo("+254711000111");
    assertThat(Phones.e164("255712345678", "255")).isEqualTo("+255712345678");
    assertThat(Phones.e164("not a phone", "255")).isNull();
    assertThat(Phones.e164(null, "255")).isNull();
  }
}
