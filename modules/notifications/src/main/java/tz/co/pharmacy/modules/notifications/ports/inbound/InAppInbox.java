package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current user's in-app messages in the current tenant (or at platform scope). */
public interface InAppInbox {

  /** Newest first. */
  Page<InAppView> list(boolean unreadOnly, PageQuery query);

  long unreadCount();

  /** 404 notification_not_found when it is not the current user's. */
  void markRead(UUID uid);

  void markAllRead();

  /**
   * {@code userUid}'s latest message and unread count in the current tenant (or at platform scope),
   * read for the live stream of another instance: a signal names the user, the stream reads the
   * rest here. For a service in that scope, not for a request.
   */
  InAppSnapshot snapshot(UUID userUid);
}
