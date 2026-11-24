package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.PasswordReset;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.PasswordResetRepository;
import tz.co.pharmacy.modules.access.persistence.SessionRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.PasswordResetService;
import tz.co.pharmacy.modules.access.ports.outbound.AccessNotices;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Password resets by email link. The token is 32 random bytes; only its hash is stored. A request
 * for an unknown or inactive email does nothing visible, so the route reveals no accounts.
 */
@Service
class PasswordResetServiceImpl implements PasswordResetService {

  static final int VALID_MINUTES = 30;

  private final UserRepository users;
  private final PasswordResetRepository resets;
  private final SessionRepository sessions;
  private final Passwords passwords;
  private final AuditLog audit;
  private final ObjectProvider<AccessNotices> notices;
  private final TransactionTemplate writes;
  private final String webUrl;

  private final Clock clock;

  /** Reset links one account may be sent in an hour; more are dropped silently. */
  static final int PER_HOUR = 3;

  private final tz.co.pharmacy.modules.access.persistence.PasskeyRepository passkeys;

  PasswordResetServiceImpl(
      UserRepository users,
      PasswordResetRepository resets,
      tz.co.pharmacy.modules.access.persistence.PasskeyRepository passkeys,
      SessionRepository sessions,
      Passwords passwords,
      AuditLog audit,
      ObjectProvider<AccessNotices> notices,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.access.web-url:http://localhost:4300}") String webUrl,
      Clock clock) {
    this.clock = clock;
    this.users = users;
    this.resets = resets;
    this.passkeys = passkeys;
    this.sessions = sessions;
    this.passwords = passwords;
    this.audit = audit;
    this.notices = notices;
    this.writes = new TransactionTemplate(transactionManager);
    this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
  }

  @Override
  public void request(String email, ClientInfo client) {
    String normalised = email == null ? "" : email.trim().toLowerCase();
    Optional<User> found = users.findByEmail(normalised).filter(User::isActive);
    if (found.isPresent() && found.get().isManaged()) {
      // A managed login has no mailbox of its own to send a link to: its pharmacy's admin resets
      // it. Same answer as for anyone else, so the sign-in name reveals nothing.
      audit.record(
          "PASSWORD_RESET_REQUESTED", "User", found.get().getUid(), null, Map.of("managed", true));
      return;
    }
    if (found.isEmpty()) {
      audit.record(
          "PASSWORD_RESET_REQUESTED",
          "User",
          null,
          null,
          Map.of("email", normalised, "known", false));
      return;
    }
    User user = found.get();
    String ip = client == null ? null : client.ip();
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () -> {
          writes.executeWithoutResult(
              status -> {
                Instant now = clock.instant();
                if (resets.countByUserUidAndCreatedAtAfter(
                        user.getUid(), now.minus(Duration.ofHours(1)))
                    >= PER_HOUR) {
                  // Same answer as always (no enumeration); the mailbox is not flooded.
                  audit.record(
                      "PASSWORD_RESET_THROTTLED",
                      "User",
                      user.getUid(),
                      null,
                      Map.of("ip", String.valueOf(ip)));
                  return;
                }
                // Only the newest link works.
                resets.closeOpen(user.getUid(), now);
                String token = "prt_" + Tokens.newToken().substring(4);
                resets.save(
                    new PasswordReset(
                        Tokens.hash(token),
                        user.getUid(),
                        clock.instant().plus(Duration.ofMinutes(VALID_MINUTES)),
                        SessionIssuer.trim(ip, 64)));
                String link = webUrl + "/reset-password?token=" + token;
                notices.ifAvailable(
                    notice ->
                        notice.passwordReset(
                            user.getUid(), user.getName(), user.getEmail(), link, VALID_MINUTES));
                audit.record(
                    "PASSWORD_RESET_REQUESTED",
                    "User",
                    user.getUid(),
                    null,
                    Map.of("ip", String.valueOf(ip)));
              });
          return null;
        });
  }

  @Override
  public void complete(String token, String newPassword) {
    Instant now = clock.instant();
    PasswordReset reset =
        resets
            .findByTokenHash(Tokens.hash(token == null ? "" : token.trim()))
            .filter(found -> found.usableAt(now))
            .orElseThrow(PasswordResetServiceImpl::invalidToken);
    User user =
        users.findByUid(reset.getUserUid()).orElseThrow(PasswordResetServiceImpl::invalidToken);
    // A reset removes the passkeys (below); an authenticator app stays, so it sets the minimum.
    String hash =
        passwords.hash(newPassword, "newPassword", user.getEmail(), user.isTwoFactorEnabled());
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () -> {
          writes.executeWithoutResult(
              status -> {
                PasswordReset row =
                    resets
                        .findByTokenHash(reset.getTokenHash())
                        .filter(found -> found.usableAt(now))
                        .orElseThrow(PasswordResetServiceImpl::invalidToken);
                row.use(now);
                User target =
                    users
                        .findByUid(user.getUid())
                        .orElseThrow(PasswordResetServiceImpl::invalidToken);
                target.changePassword(hash);
                target.notePasswordLength(Passwords.longEnoughAlone(newPassword));
                target.unlock();
                resets.closeOpen(user.getUid(), now);
                int revoked = sessions.revokeAll(user.getUid(), now, null);
                // Whoever took the account may have added a passkey; a reset removes them all
                // (adversarial review AUTH-1). The person adds theirs again.
                long passkeysRemoved = passkeys.deleteByUserUid(user.getUid());
                audit.record(
                    "PASSWORD_RESET",
                    "User",
                    user.getUid(),
                    null,
                    Map.of("sessionsRevoked", revoked, "passkeysRemoved", passkeysRemoved));
                String phone = target.hasVerifiedPhone() ? target.getPhone() : null;
                notices.ifAvailable(
                    notice ->
                        notice.passwordChanged(
                            target.getUid(), target.getName(), target.getEmail(), phone, now));
              });
          return null;
        });
  }

  private static Refusal invalidToken() {
    return Refusal.invalid(
        "invalid_token", "This reset link is not valid any more; ask for a new one.", "token");
  }
}
