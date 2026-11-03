package tz.co.pharmacy.platform.composition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.outbound.AccessNotices;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;

/**
 * Sends the access module's notices through notifications: a password reset link and an invitation
 * by email, and a new-device sign-in notice by email and in-app. Each is queued in the caller's
 * transaction and leaves after commit.
 */
@Component
class AccessNotificationsAdapter implements AccessNotices {

  private final Notifier notifier;
  private final TenantService tenants;

  AccessNotificationsAdapter(Notifier notifier, TenantService tenants) {
    this.notifier = notifier;
    this.tenants = tenants;
  }

  @Override
  public void passwordReset(
      UUID userUid, String name, String email, String link, int validMinutes) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("name", name);
    params.put("link", link);
    params.put("validMinutes", validMinutes);
    params.put("minutes", validMinutes);
    notifier.send(
        new NotificationRequest(
            "access.password_reset",
            new Recipient(userUid, name, email, null, null),
            Set.of(Channel.EMAIL),
            params,
            null));
  }

  @Override
  public void invitation(
      UUID tenantUid,
      String name,
      String email,
      String inviterName,
      String roleName,
      String link,
      int validDays) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("name", name);
    params.put("inviterName", inviterName);
    params.put("roleName", roleName);
    String pharmacyName = tenants.find(tenantUid).map(TenantView::name).orElse("");
    params.put("tenantName", pharmacyName);
    params.put("pharmacyName", pharmacyName);
    params.put("link", link);
    params.put("validDays", validDays);
    notifier.send(
        new NotificationRequest(
            "access.invitation",
            new Recipient(null, name, email, null, null),
            Set.of(Channel.EMAIL),
            params,
            null));
  }

  @Override
  public void newSignIn(
      UUID userUid, String name, String email, String device, String ip, Instant at) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("name", name);
    params.put("device", device);
    params.put("ip", ip == null ? "unknown" : ip);
    params.put("at", at.toString());
    params.put("time", at.toString());
    notifier.send(
        new NotificationRequest(
            "access.new_sign_in",
            new Recipient(userUid, name, email, null, null),
            Set.of(Channel.EMAIL, Channel.IN_APP),
            params,
            "new-sign-in:" + userUid + ":" + device));
  }
}
