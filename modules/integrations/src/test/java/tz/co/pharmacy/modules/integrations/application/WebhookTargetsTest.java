package tz.co.pharmacy.modules.integrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookTargetsTest {

  /** Names resolve from this table only; anything else is unknown. */
  private static final Map<String, String> DNS =
      Map.of(
          "hooks.example.com", "93.184.216.34",
          "rebound.example.com", "10.0.0.7",
          "cgnat.example.com", "100.72.1.1",
          "v6local.example.com", "fd12::1",
          "mapped.example.com", "::ffff:127.0.0.1",
          "v6public.example.com", "2606:2800:220:1:248:1893:25c8:1946");

  private final WebhookTargets targets =
      new WebhookTargets(
          List.of(),
          host -> {
            String address = DNS.get(host);
            if (address == null && host.matches("[0-9.:a-f\\[\\]]+")) {
              return new InetAddress[] {InetAddress.getByName(host)};
            }
            if (address == null) throw new UnknownHostException(host);
            return new InetAddress[] {InetAddress.getByName(address)};
          });

  @Test
  void acceptsHttpsToAPublicHost() {
    assertThat(targets.check("https://hooks.example.com/pharmacy").allowed()).isTrue();
    assertThat(targets.check("https://v6public.example.com/x").allowed()).isTrue();
  }

  @Test
  void refusesInternalTargets() {
    for (String url :
        List.of(
            "http://hooks.example.com/plain-http",
            "https://localhost/x",
            "https://127.0.0.1:8080/actuator",
            "https://[::1]/x",
            "https://0.0.0.0/x",
            "https://10.1.2.3/x",
            "https://172.20.0.5/x",
            "https://192.168.1.10/x",
            "https://169.254.169.254/latest/meta-data",
            "https://rebound.example.com/x",
            "https://cgnat.example.com/x",
            "https://v6local.example.com/x",
            "https://mapped.example.com/x",
            "https://db.internal/x",
            "https://printer.local/x",
            "https://intranet/x",
            "https://user:pass@hooks.example.com/x",
            "https://nowhere.example.com/x",
            "ftp://hooks.example.com/x")) {
      assertThat(targets.check(url).allowed()).as(url).isFalse();
    }
  }

  @Test
  void allowListedDevelopmentHostsMayUseHttp() {
    WebhookTargets dev =
        new WebhookTargets(
            List.of(" LocalHost "), host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
    assertThat(dev.check("http://localhost:9000/hook").allowed()).isTrue();
    assertThat(dev.check("http://127.0.0.1:9000/hook").allowed()).isFalse();
  }
}
