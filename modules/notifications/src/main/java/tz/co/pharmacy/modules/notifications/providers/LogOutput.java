package tz.co.pharmacy.modules.notifications.providers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What the {@code log} providers write. A message often carries a password-reset link, an
 * invitation link or a sign-in code, so outside a laptop or the test suite only the channel, a
 * masked address and the length are logged (OWASP Logging Cheat Sheet: never log tokens or
 * secrets). {@code pharmacy.notifications.log-bodies=true}, set by the {@code local} and {@code
 * test} profiles, logs the whole message so a developer can follow a link.
 */
@Component
class LogOutput {

  private static final Logger log = LoggerFactory.getLogger("notifications.log-provider");

  private final boolean bodies;

  LogOutput(@Value("${pharmacy.notifications.log-bodies:false}") boolean bodies) {
    this.bodies = bodies;
  }

  void write(String channel, String to, String subject, String text) {
    if (bodies) {
      log.info(
          "[{}] to={}{}\n{}",
          channel,
          to,
          subject == null ? "" : " subject=\"" + subject + "\"",
          text);
    } else {
      log.info(
          "[{}] to={} (body withheld, {} characters; no provider is configured for this channel)",
          channel,
          mask(to),
          text == null ? 0 : text.length());
    }
  }

  /** {@code +255712345678} → {@code +25…78}; {@code juma@example.com} → {@code ju…@example.com}. */
  static String mask(String address) {
    if (address == null || address.length() <= 4) {
      return "…";
    }
    int at = address.indexOf('@');
    if (at > 0) {
      return address.substring(0, Math.min(2, at)) + "…" + address.substring(at);
    }
    return address.substring(0, 3) + "…" + address.substring(address.length() - 2);
  }
}
