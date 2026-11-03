package tz.co.pharmacy.modules.notifications.ports.outbound;

/** Sends a WhatsApp message to an E.164 number. */
public interface WhatsAppSender {

  String name();

  void send(String toE164, String text);
}
