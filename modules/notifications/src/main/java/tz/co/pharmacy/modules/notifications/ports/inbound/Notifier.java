package tz.co.pharmacy.modules.notifications.ports.inbound;

/** Sends notifications. */
public interface Notifier {

  /**
   * Queues one message per channel (skipping channels the recipient lacks an address for or opted
   * out of), in the current tenant, or at platform scope when there is none (then tenant templates
   * are not consulted). Sending happens after the caller's transaction commits.
   */
  void send(NotificationRequest request);
}
