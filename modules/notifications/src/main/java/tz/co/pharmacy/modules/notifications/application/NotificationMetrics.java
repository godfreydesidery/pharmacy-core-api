package tz.co.pharmacy.modules.notifications.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

/**
 * The outbox in numbers: {@code pharmacy.notifications.queued} (messages committed for sending, in
 * the inbox for IN_APP), {@code pharmacy.notifications.sent} and {@code
 * pharmacy.notifications.failed} (an attempt every provider refused; it is retried with backoff),
 * each by channel.
 */
@Component
class NotificationMetrics {

  private final MeterRegistry meters;

  NotificationMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  void queued(Channel channel) {
    count("pharmacy.notifications.queued", "Notifications queued for sending", channel);
  }

  void sent(Channel channel) {
    count("pharmacy.notifications.sent", "Notifications a provider accepted", channel);
  }

  void failed(Channel channel) {
    count("pharmacy.notifications.failed", "Notification attempts every provider refused", channel);
  }

  private void count(String name, String description, Channel channel) {
    try {
      Counter.builder(name)
          .description(description)
          .tag("channel", channel == null ? "unknown" : channel.name())
          .register(meters)
          .increment();
    } catch (RuntimeException ignored) {
      // Metrics are bookkeeping.
    }
  }
}
