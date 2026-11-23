package tz.co.pharmacy.modules.notifications.ports.inbound;

/** A user's latest in-app message (null when none) and unread count, for the live stream. */
public record InAppSnapshot(InAppView latest, long unreadCount) {}
