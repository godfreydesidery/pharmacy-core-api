package tz.co.pharmacy.modules.notifications.ports.outbound;

/** Sends an email; throws when the provider refuses it, so the outbox retries. */
public interface EmailSender {

  /** The provider's name for the delivery log, such as {@code smtp}. */
  String name();

  void send(String to, String subject, String htmlBody, String textBody);
}
