package tz.co.pharmacy.modules.tenancy.ports.outbound;

import java.util.Map;
import java.util.UUID;

/** Tells a tenant's administrators about their subscription (notifications). */
public interface SubscriptionNotices {

  /** A template key such as {@code tenant.subscription_due}, sent in-app and by email. */
  void notifyAdmins(
      UUID tenantUid, String templateKey, Map<String, Object> params, String dedupKey);
}
