package tz.co.pharmacy.modules.notifications.events;

import java.util.UUID;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppView;

/**
 * A user received an in-app message: the live stream pushes it with the new unread count. It is
 * published after commit on the sending thread and is not stored; a missed one only delays the
 * badge until the client's next read. {@code tenantUid} is null at platform scope.
 */
public record InAppDelivered(UUID tenantUid, UUID userUid, InAppView latest, long unreadCount) {}
