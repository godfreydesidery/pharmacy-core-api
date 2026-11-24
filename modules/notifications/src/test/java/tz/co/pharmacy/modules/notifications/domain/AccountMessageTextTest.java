package tz.co.pharmacy.modules.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

/**
 * An account message keeps no live link or code at rest (adversarial review AUTH-8): sealed while
 * it waits, opened only to send, forgotten once sent or given up on.
 */
class AccountMessageTextTest {

  private static final String LINK = "https://app.example/reset-password?token=prt_live";
  private static final Instant T0 = Instant.parse("2026-11-24T08:00:00Z");

  @Test
  @DisplayName("sealed while waiting: the stored text is the sealed form; the sender opens it")
  void sealedWhileWaiting() {
    Message message = message("access");
    message.seal(text -> "sealed:" + new StringBuilder(text).reverse());
    assertThat(message.isSealed()).isTrue();
    assertThat(message.getBody()).doesNotContain("prt_live");
    assertThat(message.getHtmlBody()).doesNotContain("prt_live");
    assertThat(message.plainBody(AccountMessageTextTest::open)).contains(LINK);
    assertThat(message.plainHtmlBody(AccountMessageTextTest::open)).contains(LINK);
    // Sealed once only.
    String once = message.getBody();
    message.seal(text -> "again:" + text);
    assertThat(message.getBody()).isEqualTo(once);
  }

  @Test
  @DisplayName(
      "given up after the last attempt, the link goes; a message with attempts left keeps it")
  void forgottenWhenGivenUp() {
    Message message = message("access");
    message.seal(text -> "sealed:" + new StringBuilder(text).reverse());
    for (int attempt = 1; attempt < Message.MAX_ATTEMPTS; attempt++) {
      message.failed("smtp", "connection refused", T0.plusSeconds(60L * attempt));
      assertThat(message.isSealed()).as("attempt %d", attempt).isTrue();
    }
    message.failed("smtp", "connection refused", T0.plusSeconds(3600));
    assertThat(message.getNextAttemptAt()).isNull();
    assertThat(message.isSealed()).isFalse();
    assertThat(message.getBody()).startsWith("(Not sent.").doesNotContain("prt_live");
    assertThat(message.getHtmlBody()).isNull();
  }

  @Test
  @DisplayName("refused by the platform (the SMS cap), the code goes at once")
  void forgottenWhenRefused() {
    Message message = message("access");
    message.seal(text -> "sealed:" + text);
    message.refused("platform", "sms_cap_reached", "The plan's SMS are used up.");
    assertThat(message.getBody()).startsWith("(Not sent.").doesNotContain("prt_live");
  }

  @Test
  @DisplayName("other messages keep their text when they fail")
  void otherMessagesKeepTheirText() {
    Message message = message("stock");
    for (int attempt = 1; attempt <= Message.MAX_ATTEMPTS; attempt++) {
      message.failed("smtp", "connection refused", T0.plusSeconds(60L * attempt));
    }
    assertThat(message.getBody()).contains(LINK);
  }

  private static Message message(String category) {
    return new Message(
        category + ".test",
        category,
        Channel.EMAIL,
        UUID.randomUUID(),
        "Amina",
        "amina@example.com",
        "en",
        "Reset your password",
        "Open " + LINK,
        "<a href=\"" + LINK + "\">Reset</a>",
        null,
        T0);
  }

  private static String open(String sealed) {
    return new StringBuilder(sealed.substring("sealed:".length())).reverse().toString();
  }
}
