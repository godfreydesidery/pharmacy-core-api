package tz.co.pharmacy.modules.notifications.ports.outbound;

/** Sends a text message to an E.164 number, such as +255712345678. */
public interface SmsSender {

  String name();

  void send(String toE164, String text);
}
