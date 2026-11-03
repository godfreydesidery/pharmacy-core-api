package tz.co.pharmacy.modules.notifications.ports.inbound;

/** How a message reaches its recipient. */
public enum Channel {
  EMAIL,
  SMS,
  WHATSAPP,
  IN_APP
}
