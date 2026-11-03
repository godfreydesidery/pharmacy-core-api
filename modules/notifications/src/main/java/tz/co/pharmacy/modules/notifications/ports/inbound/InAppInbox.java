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
}
