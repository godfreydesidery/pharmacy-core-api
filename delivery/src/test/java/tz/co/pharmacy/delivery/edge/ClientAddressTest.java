package tz.co.pharmacy.delivery.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {

  private static String from(String peer, String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/sign-in");
    request.setRemoteAddr(peer);
    if (forwardedFor != null) {
      request.addHeader(ClientAddress.FORWARDED_FOR, forwardedFor);
    }
    return ClientAddress.of(request);
  }

  @Test
  @DisplayName("behind our proxies, the client is the address the edge saw")
  void behindProxies() {
    // Railway's private network is IPv6 unique local; the web app passes the edge's header on.
    assertThat(from("fd12:3456:789a::5", "41.59.12.34")).isEqualTo("41.59.12.34");
    assertThat(from("10.0.0.7", "41.59.12.34, 10.0.0.3")).isEqualTo("41.59.12.34");
    assertThat(from("100.64.0.9", "41.59.12.34")).isEqualTo("41.59.12.34");
    assertThat(from("127.0.0.1", "2c0f:fe38:2400::1")).isEqualTo("2c0f:fe38:2400::1");
    assertThat(from("::1", "41.59.12.34:51234")).isEqualTo("41.59.12.34");
  }

  @Test
  @DisplayName("X-Real-IP from our edge comes before X-Forwarded-For")
  void realIpFirst() {
    // Railway: X-Real-IP is the client; its X-Forwarded-For may end with an edge hop.
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/sign-in");
    request.setRemoteAddr("fd12::5");
    request.addHeader(ClientAddress.REAL_IP, "203.0.113.40");
    request.addHeader(ClientAddress.FORWARDED_FOR, "203.0.113.40, 198.51.100.101");
    assertThat(ClientAddress.of(request)).isEqualTo("203.0.113.40");

    // From a public connection (not our proxy) the header is ignored.
    MockHttpServletRequest direct = new MockHttpServletRequest("POST", "/api/v1/auth/sign-in");
    direct.setRemoteAddr("41.59.12.34");
    direct.addHeader(ClientAddress.REAL_IP, "8.8.8.8");
    assertThat(ClientAddress.of(direct)).isEqualTo("41.59.12.34");

    // Not an address: fall back to X-Forwarded-For.
    MockHttpServletRequest junk = new MockHttpServletRequest("POST", "/api/v1/auth/sign-in");
    junk.setRemoteAddr("10.0.0.7");
    junk.addHeader(ClientAddress.REAL_IP, "unknown");
    junk.addHeader(ClientAddress.FORWARDED_FOR, "41.59.12.34");
    assertThat(ClientAddress.of(junk)).isEqualTo("41.59.12.34");
  }

  @Test
  @DisplayName("an address a client writes into the header is never taken")
  void spoofedHeader() {
    // The client wrote 8.8.8.8; our edge appended the address it really saw.
    assertThat(from("fd12::5", "8.8.8.8, 41.59.12.34")).isEqualTo("41.59.12.34");
    // A public connection is not one of our proxies: its header is ignored.
    assertThat(from("41.59.12.34", "8.8.8.8")).isEqualTo("41.59.12.34");
  }

  @Test
  @DisplayName("without a usable header, the connection's own address")
  void noHeader() {
    assertThat(from("127.0.0.1", null)).isEqualTo("127.0.0.1");
    assertThat(from("10.0.0.7", "  ")).isEqualTo("10.0.0.7");
    assertThat(from("10.0.0.7", "unknown")).isEqualTo("10.0.0.7");
    assertThat(from("10.0.0.7", "evil.example.com")).isEqualTo("10.0.0.7");
    // Only private hops: a request from inside the network.
    assertThat(from("10.0.0.7", "192.168.1.20, 10.0.0.3")).isEqualTo("192.168.1.20");
  }

  @Test
  @DisplayName("knows private addresses from public ones")
  void privateAddresses() {
    assertThat(ClientAddress.isPrivate("10.1.2.3")).isTrue();
    assertThat(ClientAddress.isPrivate("172.20.0.1")).isTrue();
    assertThat(ClientAddress.isPrivate("192.168.0.1")).isTrue();
    assertThat(ClientAddress.isPrivate("100.100.0.1")).isTrue();
    assertThat(ClientAddress.isPrivate("fd12::1")).isTrue();
    assertThat(ClientAddress.isPrivate("fe80::1")).isTrue();
    assertThat(ClientAddress.isPrivate("::1")).isTrue();
    assertThat(ClientAddress.isPrivate("41.59.12.34")).isFalse();
    assertThat(ClientAddress.isPrivate("100.128.0.1")).isFalse();
    assertThat(ClientAddress.isPrivate("2c0f:fe38::1")).isFalse();
    assertThat(ClientAddress.isPrivate("not-an-ip")).isFalse();
    assertThat(ClientAddress.isPrivate("999.1.1.1")).isFalse();
  }
}
