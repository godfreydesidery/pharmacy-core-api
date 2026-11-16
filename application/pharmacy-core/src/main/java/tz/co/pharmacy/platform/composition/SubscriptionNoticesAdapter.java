package tz.co.pharmacy.platform.composition;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.outbound.NotificationDirectory;
import tz.co.pharmacy.modules.tenancy.ports.outbound.SubscriptionNotices;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Sends subscription notices to the tenant's administrators (those who manage users), in-app and by
 * email, in the tenant's own scope.
 */
@Component
class SubscriptionNoticesAdapter implements SubscriptionNotices {

  private final Notifier notifier;
  private final NotificationDirectory directory;

  SubscriptionNoticesAdapter(Notifier notifier, NotificationDirectory directory) {
    this.notifier = notifier;
    this.directory = directory;
  }

  @Override
  public void notifyAdmins(
      UUID tenantUid, String templateKey, Map<String, Object> params, String dedupKey) {
    ExecutionContext.callAs(
        ExecutionContext.Frame.service(
            "subscription-notices",
            ExecutionContext.correlationId().orElse(UUID.randomUUID().toString()),
            tenantUid),
        () ->
            TenantContext.callAs(
                tenantUid,
                () -> {
                  for (Recipient admin : directory.usersWithPermission("users.manage", null)) {
                    notifier.send(
                        new NotificationRequest(
                            templateKey,
                            admin,
                            EnumSet.of(Channel.EMAIL, Channel.IN_APP),
                            params,
                            dedupKey));
                  }
                  return null;
                }));
  }
}
