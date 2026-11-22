package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.Membership;
import tz.co.pharmacy.modules.access.domain.Session;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.SessionRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.Principal;
import tz.co.pharmacy.modules.access.ports.inbound.SignInResult;
import tz.co.pharmacy.modules.access.ports.outbound.AccessNotices;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.ExecutionContext.Frame;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.TenantSwitch;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Opens sessions, whichever way the user proved who they are (password, two-factor, passkey,
 * invitation). Every sign-in is audited, for the platform and in each pharmacy the user works for
 * (so its administrators see who signed in, and when), and one from a device the user has not used
 * before sends them a notice.
 */
@Component
class SessionIssuer {

  private final UserRepository users;
  private final SessionRepository sessions;
  private final MembershipRepository memberships;
  private final TenantSwitch tenantSwitch;
  private final AuditLog audit;
  private final ObjectProvider<AccessNotices> notices;
  private final TransactionTemplate writes;
  private final Duration sessionLength;
  private final boolean twoFactorRequiredForRoot;
  private final IdlePolicy idle;

  private final Clock clock;

  SessionIssuer(
      UserRepository users,
      SessionRepository sessions,
      MembershipRepository memberships,
      TenantSwitch tenantSwitch,
      AuditLog audit,
      ObjectProvider<AccessNotices> notices,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.access.session-hours:12}") long sessionHours,
      @Value("${pharmacy.access.require-2fa-for-root:false}") boolean twoFactorRequiredForRoot,
      IdlePolicy idle,
      Clock clock) {
    this.clock = clock;
    this.idle = idle;
    this.users = users;
    this.sessions = sessions;
    this.memberships = memberships;
    this.tenantSwitch = tenantSwitch;
    this.audit = audit;
    this.notices = notices;
    this.writes = new TransactionTemplate(transactionManager);
    this.sessionLength = Duration.ofHours(sessionHours);
    this.twoFactorRequiredForRoot = twoFactorRequiredForRoot;
  }

  /** Opens a session for {@code user}; {@code method} is PASSWORD, TWO_FACTOR, PASSKEY... */
  SignInResult issue(User user, ClientInfo client, String method) {
    ClientInfo from = client == null ? ClientInfo.UNKNOWN : client;
    // As the user, the transaction sees their memberships in every pharmacy.
    return ExecutionContext.callAs(
        userFrame(user),
        () ->
            TenantContext.callAsUser(
                user.getUid(),
                () ->
                    writes.execute(
                        status -> {
                          Instant now = clock.instant();
                          boolean known = sessions.existsByUserUid(user.getUid());
                          boolean sameDevice =
                              from.userAgent() != null
                                  && sessions.existsByUserUidAndUserAgent(
                                      user.getUid(), from.userAgent());
                          String token = Tokens.newToken();
                          Instant expiresAt = now.plus(sessionLength);
                          sessions.save(
                              new Session(
                                  Tokens.hash(token),
                                  user.getUid(),
                                  expiresAt,
                                  trim(from.ip(), 64),
                                  trim(from.userAgent(), 400),
                                  method,
                                  idle.initial()));
                          User row = users.findByUid(user.getUid()).orElse(user);
                          users.markSignedIn(user.getUid(), now);
                          Map<String, Object> details = new LinkedHashMap<>();
                          details.put("method", method);
                          details.put("ip", from.ip());
                          details.put("userAgent", trim(from.userAgent(), 200));
                          audit.record("SIGN_IN", "User", user.getUid(), null, details);
                          for (UUID tenantUid : workplaces(user.getUid())) {
                            tenantSwitch.within(
                                tenantUid,
                                () -> {
                                  audit.record("SIGN_IN", "User", user.getUid(), null, details);
                                  return null;
                                });
                          }
                          if (known && !sameDevice && from.userAgent() != null) {
                            notices.ifAvailable(
                                notice ->
                                    notice.newSignIn(
                                        user.getUid(),
                                        user.getName(),
                                        user.mailbox(),
                                        Devices.describe(from.userAgent()),
                                        from.ip(),
                                        now));
                          }
                          return new SignInResult(token, expiresAt, principal(row));
                        })));
  }

  /**
   * Ends a session that went unused for {@code idleMinutes}, audited as a SIGN_OUT with reason IDLE
   * for the platform and in each pharmacy the user works for (beside their sign-ins). When several
   * requests find it idle at once, only the one that ends it records it.
   */
  void endIdle(Long sessionId, User user, Integer idleMinutes) {
    // Outside the pharmacy the request may name (the app's own sign-out does), so the first entry
    // is the platform's.
    ExecutionContext.callAs(
        userFrame(user),
        () ->
            TenantContext.callAs(
                null,
                () ->
                    TenantContext.callAsUser(
                        user.getUid(),
                        () ->
                            writes.execute(
                                status -> {
                                  if (sessions.revokeIfLive(sessionId, clock.instant()) == 0) {
                                    return null;
                                  }
                                  Map<String, Object> details = new LinkedHashMap<>();
                                  details.put("reason", "IDLE");
                                  details.put("idleMinutes", idleMinutes);
                                  audit.record("SIGN_OUT", "User", user.getUid(), null, details);
                                  for (UUID tenantUid : workplaces(user.getUid())) {
                                    tenantSwitch.within(
                                        tenantUid,
                                        () -> {
                                          audit.record(
                                              "SIGN_OUT", "User", user.getUid(), null, details);
                                          return null;
                                        });
                                  }
                                  return null;
                                }))));
  }

  /** The pharmacies the user is an active member of. */
  private List<UUID> workplaces(UUID userUid) {
    return memberships.findAllByUserUid(userUid).stream()
        .filter(Membership::isActive)
        .map(Membership::getTenantUid)
        .distinct()
        .toList();
  }

  Principal principal(User user) {
    return new Principal(
        user.getUid(),
        user.getName(),
        user.getEmail(),
        user.isRootAdmin(),
        twoFactorRequired(user) && !user.isTwoFactorEnabled(),
        user.isPasswordChangeRequired());
  }

  boolean twoFactorRequired(User user) {
    return twoFactorRequiredForRoot && user.isRootAdmin();
  }

  /** The frame of a user acting on their own account, on a route that may have no session yet. */
  static Frame userFrame(User user) {
    String correlation = ExecutionContext.correlationId().orElse(UUID.randomUUID().toString());
    return new Frame(
        Actor.user(user.getUid(), user.getName(), user.getEmail()),
        correlation,
        null,
        null,
        Set.of(),
        null,
        user.isRootAdmin());
  }

  /** The frame of an anonymous step of access, such as starting a passkey sign-in. */
  static Frame serviceFrame() {
    return Frame.service(
        "access-sign-in",
        ExecutionContext.correlationId().orElse(UUID.randomUUID().toString()),
        null);
  }

  static String trim(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
