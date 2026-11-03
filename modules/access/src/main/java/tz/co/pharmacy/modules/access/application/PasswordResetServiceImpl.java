package tz.co.pharmacy.modules.access.application;

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

  PasswordResetServiceImpl(
      UserRepository users,
      PasswordResetRepository resets,
      SessionRepository sessions,
      Passwords passwords,
      AuditLog audit,
      ObjectProvider<AccessNotices> notices,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.access.web-url:http://localhost:4300}") String webUrl) {
    this.users = users;
    this.resets = resets;
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
                String token = "prt_" + Tokens.newToken().substring(4);
                resets.save(
                    new PasswordReset(
                        Tokens.hash(token),
                        user.getUid(),
                        Instant.now().plus(Duration.ofMinutes(VALID_MINUTES)),
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
    Instant now = Instant.now();
    PasswordReset reset =
        resets
            .findByTokenHash(Tokens.hash(token == null ? "" : token.trim()))
            .filter(found -> found.usableAt(now))
            .orElseThrow(PasswordResetServiceImpl::invalidToken);
    User user =
        users.findByUid(reset.getUserUid()).orElseThrow(PasswordResetServiceImpl::invalidToken);
    String hash = passwords.hash(newPassword, "newPassword");
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
                target.unlock();
                int revoked = sessions.revokeAll(user.getUid(), now, null);
                audit.record(
                    "PASSWORD_RESET",
                    "User",
                    user.getUid(),
                    null,
                    Map.of("sessionsRevoked", revoked));
              });
          return null;
        });
  }

  private static Refusal invalidToken() {
    return Refusal.invalid(
        "invalid_token", "This reset link is not valid any more; ask for a new one.", "token");
  }
}
