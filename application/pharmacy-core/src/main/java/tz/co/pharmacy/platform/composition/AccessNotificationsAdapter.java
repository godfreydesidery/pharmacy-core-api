package tz.co.pharmacy.platform.composition;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.outbound.AccessNotices;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationRequest;
import tz.co.pharmacy.modules.notifications.ports.inbound.Notifier;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
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

  // Account security notices are the platform's: no tenant, East Africa time.
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH)
          .withZone(ZoneId.of("Africa/Dar_es_Salaam"));

  @Override
  public void verificationCode(
      UUID userUid, String name, String phone, String code, int validMinutes) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_VERIFICATION_CODE,
            new Recipient(userUid, name, null, phone, null),
            Set.of(Channel.SMS),
            params("code", code, "minutes", validMinutes),
            null));
  }

  @Override
  public void emailConfirm(
      UUID userUid, String name, String newEmail, String link, int validMinutes) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_EMAIL_CONFIRM,
            new Recipient(userUid, name, newEmail, null, null),
            Set.of(Channel.EMAIL),
            params("link", link, "minutes", validMinutes),
            null));
  }

  @Override
  public void emailInUse(String email) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_EMAIL_IN_USE,
            new Recipient(null, "", email, null, null),
            Set.of(Channel.EMAIL),
            params(),
            null));
  }

  @Override
  public void emailChanged(
      UUID userUid, String name, String oldEmail, String phone, String newEmail, Instant at) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_EMAIL_CHANGED,
            new Recipient(userUid, name, oldEmail, phone, null),
            Set.of(Channel.EMAIL, Channel.SMS),
            params("newEmail", newEmail, "time", WHEN.format(at)),
            null));
  }

  @Override
  public void passwordChanged(UUID userUid, String name, String email, String phone, Instant at) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_PASSWORD_CHANGED,
            new Recipient(userUid, name, email, phone, null),
            Set.of(Channel.EMAIL, Channel.SMS),
            params("time", WHEN.format(at)),
            null));
  }

  @Override
  public void securityChanged(
      UUID userUid,
      String name,
      String email,
      String phone,
      AccessNotices.SecurityChange change,
      Instant at) {
    String key =
        switch (change) {
          case PASSKEY_ADDED -> TemplateKeys.ACCESS_PASSKEY_ADDED;
          case TWO_FACTOR_ON -> TemplateKeys.ACCESS_TWO_FACTOR_ON;
          case TWO_FACTOR_OFF -> TemplateKeys.ACCESS_TWO_FACTOR_OFF;
          case PHONE_VERIFIED -> TemplateKeys.ACCESS_PHONE_VERIFIED;
        };
    String masked =
        phone == null || phone.length() < 6
            ? ""
            : phone.substring(0, 4) + " *** " + phone.substring(phone.length() - 3);
    notifier.send(
        new NotificationRequest(
            key,
            // A newly verified phone is not yet trusted to hear about itself: email only.
            new Recipient(
                userUid,
                name,
                email,
                change == AccessNotices.SecurityChange.PHONE_VERIFIED ? null : phone,
                null),
            Set.of(Channel.EMAIL, Channel.SMS),
            params("time", WHEN.format(at), "phone", masked),
            null));
  }

  @Override
  public void phoneRemoved(UUID userUid, String name, String email, String phone) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_PHONE_REMOVED,
            new Recipient(userUid, name, email, null, null),
            Set.of(Channel.EMAIL),
            params("phone", phone),
            null));
  }

  @Override
  public void recoveryStarted(
      UUID userUid,
      String name,
      String oldEmail,
      String phone,
      String newEmail,
      Instant usableAt,
      String cancelLink) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_RECOVERY_STARTED,
            new Recipient(userUid, name, oldEmail, phone, null),
            Set.of(Channel.EMAIL, Channel.SMS),
            params(
                "newEmail", newEmail, "usableAt", WHEN.format(usableAt), "cancelLink", cancelLink),
            null));
  }

  @Override
  public void loginLinked(UUID userUid, String name, String email, String signIn, Instant at) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_LOGIN_LINKED,
            new Recipient(userUid, name, email, null, null),
            Set.of(Channel.EMAIL, Channel.IN_APP),
            params("login", signIn, "time", WHEN.format(at)),
            null));
  }

  @Override
  public void recoveryLink(String name, String newEmail, String link, Instant usableAt) {
    notifier.send(
        new NotificationRequest(
            TemplateKeys.ACCESS_RECOVERY_LINK,
            new Recipient(null, name, newEmail, null, null),
            Set.of(Channel.EMAIL),
            params("link", link, "usableAt", WHEN.format(usableAt)),
            null));
  }

  private static Map<String, Object> params(Object... pairs) {
    Map<String, Object> params = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      params.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return params;
  }
}
