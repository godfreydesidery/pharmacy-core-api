package tz.co.pharmacy.modules.access.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.RecoveryCode;
import tz.co.pharmacy.modules.access.domain.SignInChallenge;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.PasskeyRepository;
import tz.co.pharmacy.modules.access.persistence.RecoveryCodeRepository;
import tz.co.pharmacy.modules.access.persistence.SignInChallengeRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.SecurityOverview;
import tz.co.pharmacy.modules.access.ports.inbound.SignInResult;
import tz.co.pharmacy.modules.access.ports.inbound.TwoFactorService;
import tz.co.pharmacy.modules.access.ports.inbound.TwoFactorSetup;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * TOTP two-factor and recovery codes. The secret is kept encrypted; an accepted code's time step is
 * remembered, so the same code cannot be used twice. A sign-in challenge takes five tries.
 */
@Service
class TwoFactorServiceImpl implements TwoFactorService {

  static final String ISSUER = "Pharmacy Core";
  private static final int CHALLENGE_ATTEMPTS = 5;

  private final UserRepository users;
  private final RecoveryCodeRepository recoveryCodes;
  private final SignInChallengeRepository challenges;
  private final PasskeyRepository passkeys;
  private final SecretBox secrets;
  private final SessionIssuer issuer;
  private final AuthenticationServiceImpl authentication;
  private final AuditLog audit;
  private final TransactionTemplate writes;

  TwoFactorServiceImpl(
      UserRepository users,
      RecoveryCodeRepository recoveryCodes,
      SignInChallengeRepository challenges,
      PasskeyRepository passkeys,
      SecretBox secrets,
      SessionIssuer issuer,
      AuthenticationServiceImpl authentication,
      AuditLog audit,
      PlatformTransactionManager transactionManager) {
    this.users = users;
    this.recoveryCodes = recoveryCodes;
    this.challenges = challenges;
    this.passkeys = passkeys;
    this.secrets = secrets;
    this.issuer = issuer;
    this.authentication = authentication;
    this.audit = audit;
    this.writes = new TransactionTemplate(transactionManager);
  }

  @Override
  @Transactional(readOnly = true)
  public SecurityOverview overview(UUID userUid) {
    User user = user(userUid);
    return new SecurityOverview(
        user.isTwoFactorEnabled(),
        user.getTwoFactorEnabledAt(),
        recoveryCodes.countByUserUidAndUsedAtIsNull(userUid),
        passkeys.countByUserUid(userUid),
        issuer.twoFactorRequired(user));
  }

  @Override
  @Transactional
  public TwoFactorSetup begin(UUID userUid) {
    User user = user(userUid);
    if (user.isTwoFactorEnabled()) {
      throw Refusal.conflict(
          "two_factor_already_enabled", "Two-factor is on; turn it off to set up a new app.");
    }
    byte[] secret = Totp.newSecret();
    user.pendingTotpSecret(secrets.seal(secret));
    String base32 = Totp.base32(secret);
    return new TwoFactorSetup(base32, Totp.otpauthUri(ISSUER, user.getEmail(), base32));
  }

  @Override
  @Transactional
  public List<String> enable(UUID userUid, String code) {
    User user = user(userUid);
    if (user.isTwoFactorEnabled()) {
      throw Refusal.conflict("two_factor_already_enabled", "Two-factor is already on.");
    }
    if (user.getTotpSecret() == null) {
      throw Refusal.invalid("two_factor_not_started", "Start the two-factor set-up first.", "code");
    }
    long step = Totp.match(secrets.open(user.getTotpSecret()), code, Instant.now());
    if (step < 0) {
      throw invalidCode();
    }
    user.enableTwoFactor(Instant.now(), step);
    List<String> codes = replaceRecoveryCodes(userUid);
    audit.record("TWO_FACTOR_ENABLED", "User", userUid, null, null);
    return codes;
  }

  @Override
  @Transactional
  public void disable(UUID userUid, String code) {
    User user = user(userUid);
    if (!user.isTwoFactorEnabled()) {
      return;
    }
    if (!secondFactor(user, code)) {
      throw invalidCode();
    }
    user.disableTwoFactor();
    recoveryCodes.deleteAllOf(userUid);
    audit.record("TWO_FACTOR_DISABLED", "User", userUid, null, null);
  }

  @Override
  @Transactional
  public List<String> regenerateRecoveryCodes(UUID userUid, String code) {
    User user = user(userUid);
    if (!user.isTwoFactorEnabled()) {
      throw Refusal.invalid("two_factor_not_enabled", "Turn two-factor on first.", "code");
    }
    if (!secondFactor(user, code)) {
      throw invalidCode();
    }
    List<String> codes = replaceRecoveryCodes(userUid);
    audit.record("RECOVERY_CODES_REGENERATED", "User", userUid, null, null);
    return codes;
  }

  @Override
  public SignInResult verify(String challengeToken, String code, ClientInfo client) {
    ClientInfo from = client == null ? ClientInfo.UNKNOWN : client;
    Instant now = Instant.now();
    String hash = Tokens.hash(challengeToken == null ? "" : challengeToken.trim());
    SignInChallenge open =
        ExecutionContext.callAs(
            SessionIssuer.serviceFrame(),
            () ->
                writes.execute(
                    status -> {
                      SignInChallenge challenge =
                          challenges
                              .findByTokenHash(hash)
                              .filter(found -> found.openAt(now, CHALLENGE_ATTEMPTS))
                              .orElseThrow(TwoFactorServiceImpl::expired);
                      challenge.attempt();
                      return challenge;
                    }));
    User user = users.findByUid(open.getUserUid()).orElseThrow(TwoFactorServiceImpl::expired);
    Boolean accepted =
        ExecutionContext.callAs(
            SessionIssuer.userFrame(user),
            () ->
                writes.execute(
                    status -> {
                      User row = user(user.getUid());
                      if (!row.isActive() || !row.isTwoFactorEnabled()) {
                        throw expired();
                      }
                      if (!secondFactor(row, code)) {
                        return false;
                      }
                      challenges.findByTokenHash(hash).ifPresent(found -> found.consume(now));
                      return true;
                    }));
    if (!Boolean.TRUE.equals(accepted)) {
      boolean locked = authentication.recordFailure(user, user.getEmail(), from, now, "two_factor");
      if (locked) {
        throw Refusal.locked(
            "account_locked", "Too many failed sign-ins; the account is locked for a while.", 900);
      }
      throw Refusal.unauthenticated("invalid_code", "That code is not right.");
    }
    return issuer.issue(user, from, "TWO_FACTOR");
  }

  /**
   * Checks a TOTP code (refusing a replayed step) or a recovery code (consuming it), in the
   * caller's transaction.
   */
  private boolean secondFactor(User user, String code) {
    if (code == null || code.isBlank()) {
      return false;
    }
    if (user.getTotpSecret() != null && code.replaceAll("\\s", "").matches("\\d{6}")) {
      long step = Totp.match(secrets.open(user.getTotpSecret()), code, Instant.now());
      return step >= 0 && user.acceptTotpStep(step);
    }
    if (RecoveryCodes.looksLikeRecoveryCode(code)) {
      return recoveryCodes
          .findByUserUidAndCodeHashAndUsedAtIsNull(user.getUid(), RecoveryCodes.hash(code))
          .map(
              recovery -> {
                recovery.use(Instant.now());
                audit.record(
                    "RECOVERY_CODE_USED",
                    "User",
                    user.getUid(),
                    null,
                    Map.of("left", recoveryCodes.countByUserUidAndUsedAtIsNull(user.getUid()) - 1));
                return true;
              })
          .orElse(false);
    }
    return false;
  }

  private List<String> replaceRecoveryCodes(UUID userUid) {
    recoveryCodes.deleteAllOf(userUid);
    List<String> codes = RecoveryCodes.generate();
    for (String code : codes) {
      recoveryCodes.save(new RecoveryCode(userUid, RecoveryCodes.hash(code)));
    }
    return codes;
  }

  private User user(UUID userUid) {
    return users
        .findByUid(userUid)
        .orElseThrow(() -> Refusal.notFound("user_not_found", "No such user."));
  }

  private static Refusal invalidCode() {
    return Refusal.invalid("invalid_code", "That code is not right.", "code");
  }

  private static Refusal expired() {
    return Refusal.unauthenticated(
        "challenge_expired", "This sign-in has expired; sign in again with your password.");
  }
}
