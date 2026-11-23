package tz.co.pharmacy.modules.notifications.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Where an SMTP provider may point, and how it connects (INT-2). */
class SmtpRulesTest {

  /** The public-address rule, as integrations applies it, for a few hosts. */
  private final SmtpRules rules =
      new SmtpRules(
          host ->
              switch (host) {
                case "smtp.example.com", "93.184.216.34" -> Optional.empty();
                default -> Optional.of("The address points to a private or local network address.");
              },
          List.of("127.0.0.1"));

  private String reason(String host, int port) {
    return rules.check(host, port).map(SmtpRules.Refused::reason).orElse(null);
  }

  @Test
  @DisplayName("a public host on a mail port is accepted")
  void publicHost() {
    for (int port : List.of(25, 465, 587, 2525, 2587)) {
      assertThat(reason("smtp.example.com", port)).as("port " + port).isNull();
    }
    assertThat(reason(" SMTP.Example.com ", 587)).isNull();
  }

  @Test
  @DisplayName("other ports are refused: smtp_port_not_allowed")
  void ports() {
    for (int port : List.of(1, 22, 80, 443, 3306, 5432, 6379, 8080, 11211)) {
      assertThat(reason("smtp.example.com", port))
          .as("port " + port)
          .isEqualTo("smtp_port_not_allowed");
    }
  }

  @Test
  @DisplayName("internal hosts are refused, and so is anything but a bare host name")
  void hosts() {
    assertThat(reason("10.0.0.5", 587)).isEqualTo("smtp_host_not_allowed");
    assertThat(reason("metadata.internal", 587)).isEqualTo("smtp_host_not_allowed");
    for (String malformed :
        List.of("smtp://smtp.example.com", "smtp.example.com:587", "user@smtp.example.com", "")) {
      assertThat(reason(malformed, 587)).as(malformed).isEqualTo("smtp_host_not_allowed");
    }
  }

  @Test
  @DisplayName("an allowed development host skips the host and port rules")
  void allowedHost() {
    assertThat(reason("127.0.0.1", 1)).isNull();
    assertThat(reason("127.0.0.2", 587)).isEqualTo("smtp_host_not_allowed");
  }

  @Test
  @DisplayName("465 is implicit TLS; every other port requires STARTTLS; identity is checked")
  void tls() {
    Properties smtps = SmtpRules.connection(465, true);
    assertThat(smtps.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
    assertThat(smtps.getProperty("mail.smtp.starttls.required")).isNull();
    for (int port : List.of(25, 587, 2525, 2587)) {
      Properties props = SmtpRules.connection(port, true);
      assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
      assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
      assertThat(props.getProperty("mail.smtp.ssl.enable")).isNull();
    }
    assertThat(SmtpRules.connection(587, false).getProperty("mail.smtp.ssl.checkserveridentity"))
        .isEqualTo("true");
  }
}
