package tz.co.pharmacy.modules.integrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A delivery connects to the addresses checked just before it, never resolving the host's name a
 * second time (adversarial review INT-14, DNS rebinding).
 */
class WebhookPinningTest {

  private static final String SECRET = "whsec_" + "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

  @Test
  void connectsToTheCheckedAddressWithoutResolvingAgain() throws Exception {
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    HttpServer receiver = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
    List<String> hosts = new CopyOnWriteArrayList<>();
    List<String> ids = new CopyOnWriteArrayList<>();
    receiver.createContext(
        "/hook",
        exchange -> {
          hosts.add(exchange.getRequestHeaders().getFirst("Host"));
          ids.add(exchange.getRequestHeaders().getFirst("webhook-id"));
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    receiver.start();
    try {
      int port = receiver.getAddress().getPort();
      // The name exists only in this table, which answers once: a second lookup (as a client that
      // resolves the name itself would make) finds nothing.
      AtomicInteger lookups = new AtomicInteger();
      WebhookTargets targets =
          new WebhookTargets(
              List.of("hooks.pinned.test"),
              host -> {
                if (lookups.incrementAndGet() > 1) {
                  throw new java.net.UnknownHostException(host);
                }
                return new InetAddress[] {loopback};
              });
      WebhookSender sender = new WebhookSender(targets, Clock.systemUTC(), true);

      WebhookSender.Outcome outcome =
          sender.send(
              "http://hooks.pinned.test:" + port + "/hook?source=pharmacy",
              List.of(SECRET),
              "evt_1",
              "sale.completed",
              "{\"id\":\"evt_1\"}");

      assertThat(outcome.delivered()).as(outcome.error()).isTrue();
      assertThat(outcome.responseStatus()).isEqualTo(204);
      assertThat(lookups).hasValue(1);
      assertThat(hosts).containsExactly("hooks.pinned.test:" + port);
      assertThat(ids).containsExactly("evt_1");
    } finally {
      receiver.stop(0);
    }
  }

  @Test
  void refusesAHeaderWithALineBreak() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                PinnedPost.head(
                    java.net.URI.create("https://hooks.example.com/x"),
                    java.util.Map.of("X-Pharmacy-Event", "sale.completed\r\nX-Evil: 1"),
                    2))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
