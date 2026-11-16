package tz.co.pharmacy.modules.notifications.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class ProviderErrorsTest {

  @Test
  void transportFailuresReadAsPlainWords() {
    ProviderException tls =
        new ProviderException(
            "beem is unreachable: Unrecognized SSL message, plaintext connection?",
            new SSLException("Unrecognized SSL message, plaintext connection?"));
    assertThat(ProviderErrors.readable(tls, "Beem"))
        .isEqualTo(
            "Could not reach Beem (TLS handshake failed). Check the endpoint address and port,"
                + " and the credentials.")
        .doesNotContain("plaintext");
    assertThat(
            ProviderErrors.readable(
                new ProviderException("x", new UnknownHostException("api.example.tz")), "SMTP"))
        .isEqualTo("Could not find SMTP's server (api.example.tz). Check the endpoint address.");
    assertThat(ProviderErrors.readable(new ProviderException("x", new ConnectException()), null))
        .startsWith("Could not connect to the provider (connection refused)");
    assertThat(
            ProviderErrors.readable(
                new ProviderException("x", new HttpTimeoutException("timed out")), "Twilio"))
        .isEqualTo("Twilio did not answer in time. Try again, or check the endpoint.");
  }

  @Test
  void providerAnswersPassThroughAndOthersStayVague() {
    assertThat(
            ProviderErrors.readable(
                new ProviderException("Beem answered HTTP 401: invalid api key"), "Beem"))
        .isEqualTo("Beem answered HTTP 401: invalid api key");
    assertThat(ProviderErrors.readable(new IllegalStateException("NPE at Foo.java:12"), "Beem"))
        .isEqualTo(
            "Could not send through Beem. Check its settings; the server log has the details.");
  }
}
