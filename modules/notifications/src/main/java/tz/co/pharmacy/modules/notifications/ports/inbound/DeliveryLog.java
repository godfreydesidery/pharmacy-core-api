package tz.co.pharmacy.modules.notifications.ports.inbound;

import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current tenant's messages, newest first. */
public interface DeliveryLog {

  Page<DeliveryView> list(Channel channel, String status, PageQuery query);

  /** 404 delivery_not_found. */
  DeliveryDetail detail(java.util.UUID messageUid);

  /**
   * Sends the same message again as a new delivery (email, SMS or WhatsApp; 422 cannot_resend for
   * in-app). Secret links are sent as they were: a used or expired link stays unusable.
   */
  DeliveryView resend(java.util.UUID messageUid);
}
