package tz.co.pharmacy.modules.notifications.ports.inbound;

import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current tenant's messages, newest first. */
public interface DeliveryLog {

  Page<DeliveryView> list(Channel channel, String status, PageQuery query);
}
