package tz.co.pharmacy.modules.access.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.EmailChange;
import tz.co.pharmacy.modules.access.domain.PhoneCode;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.EmailChangeRepository;
import tz.co.pharmacy.modules.access.persistence.PhoneCodeRepository;
import tz.co.pharmacy.modules.access.persistence.RecoveryCodeRepository;
import tz.co.pharmacy.modules.access.persistence.SessionRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.AccountSecurity;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.outbound.AccessNotices;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.audit.AuditEntryView;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * The person's own account security: email change, phone verification, reset by phone, assisted
 * recovery and their security history. Following NIST SP 800-63B and OWASP ASVS: changes to how the
 * person signs in need the password again (and the second factor when on); links and codes are
 * single-use, short-lived and stored as hashes; wrong codes are counted; every change is told to
 * the person's other channels; and no answer reveals whether an address or number has an account.
 */
@Service
class AccountSecurityImpl implements AccountSecurity {

  static final String VERIFY = "VERIFY";
  static final String RESET = "RESET";

  /** Codes sent to one account for one purpose in an hour, at most. */
  static final int CODES_PER_HOUR = 3;

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Times in messages, as people in Tanzania read them. */
  private static final java.time.format.DateTimeFormatter EAST_AFRICA =
      java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", java.util.Locale.ENGLISH)
          .withZone(java.time.ZoneId.of("Africa/Dar_es_Salaam"));

  private final UserRepository users;
  private final EmailChangeRepository changes;
  private final PhoneCodeRepository codes;
  private final SessionRepository sessions;
  private final RecoveryCodeRepository recoveryCodes;
  private final tz.co.pharmacy.modules.access.persistence.PasskeyRepository passkeys;
  private final tz.co.pharmacy.modules.access.persistence.PasswordResetRepository resets;

  /**
   * A phone verified longer ago than this must be verified again before it can reset a password.
   */
  static final Duration PHONE_RESET_FRESHNESS = Duration.ofDays(180);

  private final Passwords passwords;
  private final SecretBox secrets;
  private final TwoFactorServiceImpl twoFactor;
  private final AuthenticationServiceImpl authentication;
  private final SignInGuard guard;
  private final tz.co.pharmacy.modules.access.persistence.MembershipRepository memberships;
  private final tz.co.pharmacy.platform.persistence.TenantSwitch tenantSwitch;
  private final AuditLog audit;
  private final ObjectProvider<AccessNotices> notices;
  private final TransactionTemplate writes;
  private final String webUrl;
  private final Clock clock;

  AccountSecurityImpl(
      UserRepository users,
      EmailChangeRepository changes,
      PhoneCodeRepository codes,
      SessionRepository sessions,
      RecoveryCodeRepository recoveryCodes,
      tz.co.pharmacy.modules.access.persistence.PasskeyRepository passkeys,
      tz.co.pharmacy.modules.access.persistence.PasswordResetRepository resets,
      Passwords passwords,
      SecretBox secrets,
      TwoFactorServiceImpl twoFactor,
      AuthenticationServiceImpl authentication,
      SignInGuard guard,
      tz.co.pharmacy.modules.access.persistence.MembershipRepository memberships,
      tz.co.pharmacy.platform.persistence.TenantSwitch tenantSwitch,
      AuditLog audit,
      ObjectProvider<AccessNotices> notices,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.access.web-url:http://localhost:4300}") String webUrl,
      Clock clock) {
    this.users = users;
    this.changes = changes;
    this.codes = codes;
    this.sessions = sessions;
    this.recoveryCodes = recoveryCodes;
    this.passkeys = passkeys;
    this.resets = resets;
    this.passwords = passwords;
    this.secrets = secrets;
    this.twoFactor = twoFactor;
    this.authentication = authentication;
    this.guard = guard;
    this.memberships = memberships;
    this.tenantSwitch = tenantSwitch;
    this.audit = audit;
    this.notices = notices;
    this.writes = new TransactionTemplate(transactionManager);
    this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ email

  @Override
  @Transactional
  public void requestEmailChange(
      UUID userUid, String newEmail, String password, String code, ClientInfo client) {
    User user = personal(userUid);
    String email = Checks.email(newEmail, "newEmail");
    reauthenticate(user, password, code);
    if (email.equals(user.getEmail())) {
      throw Refusal.invalid("same_email", "That is already your sign-in email.", "newEmail");
    }
    Instant now = clock.instant();
    closeOpen(user.getUid(), now);
    audit.record(
        "EMAIL_CHANGE_REQUESTED", "User", user.getUid(), null, Map.of("newEmail", mask(email)));
    if (users.findByEmail(email).isPresent()) {
      // Same answer as for a free address; only the owner of that mailbox learns it has an account.
      notices.ifAvailable(notice -> notice.emailInUse(email));
      return;
    }
    String token = "pec_" + Tokens.newToken().substring(4);
    changes.save(
        new EmailChange(
            user.getUid(),
            EmailChange.CHANGE,
            user.getEmail(),
            email,
            Tokens.hash(token),
            null,
            null,
            now.plus(Duration.ofMinutes(EMAIL_LINK_MINUTES)),
            false,
            null,
            ip(client)));
    String link = webUrl + "/confirm-email?token=" + token;
    notices.ifAvailable(
        notice ->
            notice.emailConfirm(user.getUid(), user.getName(), email, link, EMAIL_LINK_MINUTES));
  }

  @Override
  public void confirmEmail(String token, String newPassword) {
    Instant now = clock.instant();
    EmailChange change =
        changes
            .findByTokenHash(Tokens.hash(token == null ? "" : token.trim()))
            .filter(found -> !found.isRevert() && found.openAt(now))
            .orElseThrow(AccountSecurityImpl::invalidToken);
    if (!change.usableAt(now)) {
      throw Refusal.invalid(
              "recovery_on_hold",
              "This recovery link works from "
                  + EAST_AFRICA.format(change.getNotBefore())
                  + " (East Africa Time).",
              "token")
          .with("notBefore", change.getNotBefore());
    }
    String hash =
        change.isRecovery()
            ? passwords.hash(
                Optional.ofNullable(newPassword)
                    .filter(p -> !p.isBlank())
                    .orElseThrow(
                        () ->
                            Refusal.invalid(
                                "password_required", "Choose a new password.", "newPassword")),
                "newPassword")
            : null;
    User user = users.findByUid(change.getUserUid()).orElseThrow(AccountSecurityImpl::invalidToken);
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () -> {
          writes.executeWithoutResult(
              status -> {
                EmailChange row =
                    changes
                        .findByTokenHash(change.getTokenHash())
                        .filter(found -> found.usableAt(now))
                        .orElseThrow(AccountSecurityImpl::invalidToken);
                User target =
                    users
                        .findByUid(row.getUserUid())
                        .orElseThrow(AccountSecurityImpl::invalidToken);
                if (users.findByEmail(row.getNewEmail()).isPresent()) {
                  throw Refusal.conflict(
                      "email_taken", "That address has an account now; ask for another change.");
                }
                row.use(now);
                String oldEmail = target.getEmail();
                target.changeEmail(row.getNewEmail());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("from", mask(oldEmail));
                details.put("to", mask(row.getNewEmail()));
                // Old reset links went to the old address: they stop working.
                resets.closeOpen(target.getUid(), now);
                if (row.isRecovery()) {
                  target.changePassword(hash);
                  target.unlock();
                  if (row.isResetTwoFactor()) {
                    target.disableTwoFactor();
                    recoveryCodes.deleteAllOf(target.getUid());
                  }
                  details.put("sessionsRevoked", sessions.revokeAll(target.getUid(), now, null));
                  details.put("passkeysRemoved", passkeys.deleteByUserUid(target.getUid()));
                  details.put("twoFactorReset", row.isResetTwoFactor());
                  audit.record("RECOVERY_COMPLETED", "User", target.getUid(), null, details);
                } else {
                  // Every session signs in again with the new address (OWASP ASVS V3.3).
                  details.put("sessionsRevoked", sessions.revokeAll(target.getUid(), now, null));
                  audit.record("EMAIL_CHANGED", "User", target.getUid(), null, details);
                }
                // The old address may undo it for a week (AUTH-13); not after a recovery, whose old
                // mailbox is the one that was lost, and which it was told of and could cancel.
                String revert = row.isRecovery() ? null : revertLink(target, oldEmail, row, now);
                String phone = target.hasVerifiedPhone() ? target.getPhone() : null;
                notices.ifAvailable(
                    notice -> {
                      notice.emailChanged(
                          target.getUid(),
                          target.getName(),
                          oldEmail,
                          phone,
                          mask(row.getNewEmail()),
                          now,
                          revert);
                      if (row.isRecovery()) {
                        notice.passwordChanged(
                            target.getUid(), target.getName(), row.getNewEmail(), null, now);
                      }
                    });
              });
          return null;
        });
  }

  @Override
  public void cancelRecovery(String token) {
    Instant now = clock.instant();
    EmailChange change =
        changes
            .findByCancelTokenHash(Tokens.hash(token == null ? "" : token.trim()))
            .filter(found -> found.openAt(now))
            .orElseThrow(AccountSecurityImpl::invalidToken);
    User user = users.findByUid(change.getUserUid()).orElseThrow(AccountSecurityImpl::invalidToken);
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () -> {
          writes.executeWithoutResult(
              status -> {
                changes
                    .findByCancelTokenHash(change.getCancelTokenHash())
                    .filter(found -> found.openAt(now))
                    .orElseThrow(AccountSecurityImpl::invalidToken)
                    .cancel(now);
                audit.record(
                    "RECOVERY_CANCELLED",
                    "User",
                    user.getUid(),
                    null,
                    Map.of("to", mask(change.getNewEmail())));
              });
          return null;
        });
  }

  /**
   * Records the single-use link that undoes this change, valid {@link #EMAIL_REVERT_DAYS} days, and
   * returns it for the notice to the old address. In the change's transaction.
   */
  private String revertLink(User target, String oldEmail, EmailChange change, Instant now) {
    String token = "per_" + Tokens.newToken().substring(4);
    changes.save(
        new EmailChange(
            target.getUid(),
            EmailChange.REVERT,
            change.getNewEmail(),
            oldEmail,
            Tokens.hash(token),
            null,
            null,
            now.plus(Duration.ofDays(EMAIL_REVERT_DAYS)),
            false,
            null,
            change.getRequestedIp()));
    return webUrl + "/revert-email?token=" + token;
  }

  @Override
  public void revertEmail(String token, String newPassword) {
    Instant now = clock.instant();
    EmailChange revert =
        changes
            .findByTokenHash(Tokens.hash(token == null ? "" : token.trim()))
            .filter(found -> found.isRevert() && found.openAt(now))
            .orElseThrow(AccountSecurityImpl::invalidToken);
    User user = users.findByUid(revert.getUserUid()).orElseThrow(AccountSecurityImpl::invalidToken);
    if (newPassword == null || newPassword.isBlank()) {
      throw Refusal.invalid("password_required", "Choose a new password.", "newPassword");
    }
    // The rule is checked now; the costly hash only once the link is known to be good (AUTH-6).
    Passwords.acceptable(newPassword, "newPassword", revert.getNewEmail());
    String hash = passwords.hash(newPassword, "newPassword", revert.getNewEmail());
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () -> {
          writes.executeWithoutResult(
              status -> {
                EmailChange row =
                    changes
                        .findByTokenHash(revert.getTokenHash())
                        .filter(found -> found.isRevert() && found.openAt(now))
                        .orElseThrow(AccountSecurityImpl::invalidToken);
                User target =
                    users
                        .findByUid(row.getUserUid())
                        .orElseThrow(AccountSecurityImpl::invalidToken);
                String restored = row.getNewEmail();
                if (users
                    .findByEmail(restored)
                    .filter(other -> !other.getUid().equals(target.getUid()))
                    .isPresent()) {
                  throw Refusal.conflict(
                      "email_taken", "That address has another account now; contact support.");
                }
                row.use(now);
                Instant changedAt = row.getCreatedAt() == null ? now : row.getCreatedAt();
                for (EmailChange other :
                    changes.findByUserUidOrderByCreatedAtDesc(target.getUid())) {
                  // Whatever was asked for since: open changes, and revert links of later changes
                  // (the earliest revert wins, so a later mover cannot undo the owner's undo).
                  boolean later =
                      other.getCreatedAt() != null && other.getCreatedAt().isAfter(changedAt);
                  if (other.openAt(now) && (!other.isRevert() || later)) {
                    other.cancel(now);
                  }
                }
                String moved = target.getEmail();
                target.changeEmail(restored);
                target.changePassword(hash);
                target.unlock();
                boolean twoFactorReset =
                    target.isTwoFactorEnabled()
                        && target.getTwoFactorEnabledAt() != null
                        && target.getTwoFactorEnabledAt().isAfter(changedAt);
                if (twoFactorReset) {
                  // Turned on after the change: by whoever moved the account, with their device.
                  target.disableTwoFactor();
                  recoveryCodes.deleteAllOf(target.getUid());
                }
                resets.closeOpen(target.getUid(), now);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("from", mask(moved));
                details.put("to", mask(restored));
                details.put("sessionsRevoked", sessions.revokeAll(target.getUid(), now, null));
                details.put("passkeysRemoved", passkeys.deleteByUserUid(target.getUid()));
                details.put("twoFactorReset", twoFactorReset);
                audit.record("EMAIL_CHANGE_REVERTED", "User", target.getUid(), null, details);
                String phone = target.hasVerifiedPhone() ? target.getPhone() : null;
                notices.ifAvailable(
                    notice ->
                        notice.passwordChanged(
                            target.getUid(), target.getName(), restored, phone, now));
              });
          return null;
        });
  }

  @Override
  @Transactional(readOnly = true)
  public String pendingEmail(UUID userUid) {
    Instant now = clock.instant();
    return changes.findByUserUidOrderByCreatedAtDesc(userUid).stream()
        .filter(change -> change.openAt(now) && !change.isRecovery() && !change.isRevert())
        .map(EmailChange::getNewEmail)
        .findFirst()
        .orElse(null);
  }

  // ------------------------------------------------------------------ phone

  @Override
  @Transactional
  public void sendPhoneCode(UUID userUid, String phone, ClientInfo client) {
    User user = personal(userUid);
    String number = Phones.normalise(phone);
    if (number.equals(user.getPhone()) && user.hasVerifiedPhone()) {
      throw Refusal.invalid("already_verified", "That phone is already verified.", "phone");
    }
    send(user, VERIFY, number, client);
  }

  @Override
  // A wrong code is counted even though the call is refused.
  @Transactional(noRollbackFor = Refusal.class)
  public void verifyPhone(UUID userUid, String code) {
    User user = personal(userUid);
    Instant now = clock.instant();
    PhoneCode sent =
        codes
            .findFirstByUserUidAndPurposeOrderByCreatedAtDesc(user.getUid(), VERIFY)
            .orElseThrow(AccountSecurityImpl::invalidCode);
    if (!sent.tryCode(smsCode(code), now)) {
      throw invalidCode();
    }
    String number = sent.getPhone();
    users
        .findByPhoneAndPhoneVerifiedAtIsNotNull(number)
        .filter(other -> !other.getUid().equals(user.getUid()))
        .ifPresent(
            earlier -> {
              // A recycled number: the person holding it now takes it.
              earlier.losePhone();
              users.flush();
              audit.record(
                  "PHONE_LOST", "User", earlier.getUid(), null, Map.of("phone", mask(number)));
              notices.ifAvailable(
                  notice ->
                      notice.phoneRemoved(
                          earlier.getUid(), earlier.getName(), earlier.mailbox(), number));
            });
    user.verifyPhone(number, now);
    audit.record("PHONE_VERIFIED", "User", user.getUid(), null, Map.of("phone", mask(number)));
    notices.ifAvailable(
        notice ->
            notice.securityChanged(
                user.getUid(),
                user.getName(),
                user.mailbox(),
                number,
                AccessNotices.SecurityChange.PHONE_VERIFIED,
                now));
  }

  @Override
  @Transactional
  public void removePhone(UUID userUid, String password) {
    User user = personal(userUid);
    if (!passwords.matches(password, user.getPasswordHash())) {
      throw invalidPassword();
    }
    if (user.getPhone() == null) {
      return;
    }
    String old = user.getPhone();
    user.losePhone();
    audit.record("PHONE_REMOVED", "User", user.getUid(), null, Map.of("phone", mask(old)));
  }

  @Override
  public void requestPhoneReset(String phone, ClientInfo client) {
    String number;
    try {
      number = Phones.normalise(phone);
    } catch (Refusal notAPhone) {
      return;
    }
    Optional<User> found =
        users
            .findByPhoneAndPhoneVerifiedAtIsNotNull(number)
            .filter(User::isActive)
            .filter(user -> !user.isManaged())
            // A root administrator holds every pharmacy: a SIM swap must not be enough to take
            // the account, so root resets go by email (with its second factor) only.
            .filter(user -> !user.isRootAdmin())
            // A number verified long ago may have been recycled to someone else (AUTH-4).
            .filter(user -> recentlyVerifiedPhone(user, clock.instant()));
    if (found.isEmpty()) {
      return;
    }
    User user = found.get();
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () -> {
          writes.executeWithoutResult(
              status -> {
                try {
                  send(user, RESET, number, client);
                } catch (Refusal tooMany) {
                  // Same answer either way; the limit still holds.
                  status.setRollbackOnly();
                }
              });
          return null;
        });
  }

  @Override
  public void completePhoneReset(String phone, String code, String newPassword) {
    Instant now = clock.instant();
    String number;
    try {
      number = Phones.normalise(phone);
    } catch (Refusal notAPhone) {
      throw invalidCode();
    }
    User user =
        users
            .findByPhoneAndPhoneVerifiedAtIsNotNull(number)
            .filter(User::isActive)
            .filter(found -> !found.isManaged())
            .filter(found -> !found.isRootAdmin())
            .filter(found -> recentlyVerifiedPhone(found, now))
            .orElseThrow(AccountSecurityImpl::invalidCode);
    // The rule is checked now; the costly hash only once the code is right (AUTH-6).
    Passwords.acceptable(newPassword, "newPassword", user.getEmail());
    boolean[] ok = {false};
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () -> {
          writes.executeWithoutResult(
              status -> {
                PhoneCode sent =
                    codes
                        .findFirstByUserUidAndPurposeOrderByCreatedAtDesc(user.getUid(), RESET)
                        .filter(found -> found.getPhone().equals(number))
                        .orElse(null);
                if (sent == null || !sent.tryCode(smsCode(code), now)) {
                  // Keep the counted try, then refuse.
                  return;
                }
                User target = users.findByUid(user.getUid()).orElseThrow();
                target.changePassword(passwords.hash(newPassword, "newPassword", user.getEmail()));
                target.unlock();
                resets.closeOpen(target.getUid(), now);
                int revoked = sessions.revokeAll(target.getUid(), now, null);
                long passkeysRemoved = passkeys.deleteByUserUid(target.getUid());
                audit.record(
                    "PASSWORD_RESET",
                    "User",
                    target.getUid(),
                    null,
                    Map.of(
                        "by",
                        "phone",
                        "sessionsRevoked",
                        revoked,
                        "passkeysRemoved",
                        passkeysRemoved));
                notices.ifAvailable(
                    notice ->
                        notice.passwordChanged(
                            target.getUid(), target.getName(), target.getEmail(), number, now));
                ok[0] = true;
              });
          return null;
        });
    if (!ok[0]) {
      throw invalidCode();
    }
  }

  // ------------------------------------------------------- linking a login

  @Override
  public void linkStaffLogin(
      UUID userUid,
      String signIn,
      String loginPassword,
      String password,
      String code,
      ClientInfo client) {
    Instant now = clock.instant();
    ClientInfo from = client == null ? ClientInfo.UNKNOWN : client;
    String login = signIn == null ? "" : signIn.trim().toLowerCase();
    // The person first: their own password (and second factor), as for an email change.
    User person =
        writes.execute(
            status -> {
              User found = personal(userUid);
              reauthenticate(found, password, code);
              return found;
            });
    // Then the login: as hard to guess here as at the sign-in page.
    User staff = users.findByEmail(login).orElse(null);
    guard.checkAccount(staff, login, now);
    boolean proved =
        staff != null
            && staff.isManaged()
            && staff.isActive()
            && passwords.matches(loginPassword, staff.getPasswordHash());
    if (!proved) {
      boolean locked = authentication.recordFailure(staff, login, from, now, "link");
      if (locked) {
        throw guard.locked(guard.lockFor());
      }
      throw Refusal.invalid("invalid_login", "The staff login or its password is wrong.", "signIn");
    }
    UUID pharmacy = staff.getManagedByTenantUid();
    writes.executeWithoutResult(
        status -> {
          User target = users.findByUid(staff.getUid()).orElseThrow();
          tenantSwitch.within(
              pharmacy,
              () -> {
                if (memberships.existsByUserUidAndTenantUid(person.getUid(), pharmacy)) {
                  throw Refusal.conflict(
                      "already_a_member",
                      "Your account is already in that pharmacy; ask its admin to remove one.");
                }
                memberships
                    .findByUserUidAndTenantUid(target.getUid(), pharmacy)
                    .ifPresent(membership -> membership.moveTo(person.getUid()));
                // The pharmacy's record: its login now signs in as a personal account. Nothing
                // about the account's other pharmacies.
                audit.record(
                    "STAFF_LOGIN_LINKED",
                    "User",
                    target.getUid(),
                    null,
                    Map.of("login", login, "personalAccount", person.getUid().toString()));
                return null;
              });
          target.linkTo(person.getUid(), now);
          int revoked = sessions.revokeAll(target.getUid(), now, null);
          audit.record(
              "STAFF_LOGIN_LINKED",
              "User",
              person.getUid(),
              null,
              Map.of("login", login, "sessionsEnded", revoked));
          notices.ifAvailable(
              notice ->
                  notice.loginLinked(
                      person.getUid(), person.getName(), person.getEmail(), login, now));
        });
  }

  // ------------------------------------------------------ leaving a pharmacy

  @Override
  public void leavePharmacy(UUID userUid, UUID tenantUid, String password, String code) {
    UUID pharmacy = tz.co.pharmacy.platform.kernel.Checks.required(tenantUid, "tenantUid");
    writes.executeWithoutResult(
        status -> {
          User person = personal(userUid);
          reauthenticate(person, password, code);
          tenantSwitch.within(
              pharmacy,
              () -> {
                tz.co.pharmacy.modules.access.domain.Membership membership =
                    memberships
                        .findByUserUidAndTenantUid(person.getUid(), pharmacy)
                        .filter(tz.co.pharmacy.modules.access.domain.Membership::isActive)
                        .orElseThrow(
                            () ->
                                Refusal.notFound(
                                    "membership_not_found", "You do not work in that pharmacy."));
                if (managesPeople(membership)
                    && memberships.findAll().stream()
                        .filter(other -> pharmacy.equals(other.getTenantUid()))
                        .filter(tz.co.pharmacy.modules.access.domain.Membership::isActive)
                        .filter(other -> !other.getUid().equals(membership.getUid()))
                        .noneMatch(AccountSecurityImpl::managesPeople)) {
                  throw Refusal.conflict(
                      "last_admin",
                      "You are the only one who can manage this pharmacy's people: give the role"
                          + " to someone else first.");
                }
                membership.change(
                    membership.getRole(),
                    membership.isAllBranches(),
                    membership.getBranchUids(),
                    false);
                membership.left(person.getName(), person.getEmail(), person.getPhone());
                audit.record("MEMBER_LEFT", "User", person.getUid(), null, Map.of("by", "self"));
                return null;
              });
          audit.record(
              "LEFT_PHARMACY",
              "User",
              person.getUid(),
              null,
              Map.of("tenant", pharmacy.toString()));
        });
  }

  private static boolean managesPeople(tz.co.pharmacy.modules.access.domain.Membership membership) {
    return membership
        .getRole()
        .getPermissions()
        .contains(tz.co.pharmacy.modules.access.ports.inbound.Permissions.USERS_MANAGE);
  }

  // --------------------------------------------------------------- recovery

  @Override
  @Transactional
  public RecoveryView startRecovery(
      UUID userUid, String newEmail, boolean resetTwoFactor, String reason, ClientInfo client) {
    User user = personal(userUid);
    String email = Checks.email(newEmail, "newEmail");
    String why = Checks.text(reason, "reason", 500);
    if (email.equals(user.getEmail())) {
      throw Refusal.invalid(
          "same_email", "Recovery moves the account to a new address.", "newEmail");
    }
    if (users.findByEmail(email).isPresent()) {
      // The root admin acts for the platform and may know this; the person cannot use it.
      throw Refusal.conflict("email_taken", "That address already has an account.");
    }
    Instant now = clock.instant();
    closeOpen(user.getUid(), now);
    Instant usableFrom = now.plus(Duration.ofHours(RECOVERY_HOLD_HOURS));
    Instant expires = usableFrom.plus(Duration.ofHours(RECOVERY_VALID_HOURS));
    String token = "prc_" + Tokens.newToken().substring(4);
    String cancel = "pcr_" + Tokens.newToken().substring(4);
    EmailChange change =
        changes.save(
            new EmailChange(
                user.getUid(),
                EmailChange.RECOVERY,
                user.getEmail(),
                email,
                Tokens.hash(token),
                Tokens.hash(cancel),
                usableFrom,
                expires,
                resetTwoFactor,
                why,
                ip(client)));
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("to", mask(email));
    details.put("usableFrom", usableFrom.toString());
    details.put("resetTwoFactor", resetTwoFactor);
    details.put("reason", why);
    audit.record("RECOVERY_STARTED", "User", user.getUid(), null, details);
    String phone = user.hasVerifiedPhone() ? user.getPhone() : null;
    notices.ifAvailable(
        notice -> {
          notice.recoveryStarted(
              user.getUid(),
              user.getName(),
              user.getEmail(),
              phone,
              mask(email),
              usableFrom,
              webUrl + "/cancel-recovery?token=" + cancel);
          notice.recoveryLink(
              user.getName(), email, webUrl + "/confirm-email?token=" + token, usableFrom);
        });
    return new RecoveryView(change.getUid(), email, usableFrom, expires);
  }

  @Override
  @Transactional(readOnly = true)
  public tz.co.pharmacy.modules.access.ports.inbound.UserView account(String email) {
    String normalised = email == null ? "" : email.trim().toLowerCase();
    return users
        .findByEmail(normalised)
        .map(AccessViews::user)
        .orElseThrow(() -> Refusal.notFound("user_not_found", "No account has that email."));
  }

  // ---------------------------------------------------------------- history

  @Override
  @Transactional(readOnly = true)
  public Page<SecurityEventView> history(UUID userUid, PageQuery query) {
    Page<AuditEntryView> page = audit.securityEvents(userUid, query);
    return page.map(
        entry -> {
          Map<String, Object> after = entry.after() == null ? Map.of() : entry.after();
          Object ip = after.get("ip");
          Object device = after.get("userAgent");
          return new SecurityEventView(
              entry.uid(),
              entry.at(),
              entry.action(),
              ip == null || "null".equals(String.valueOf(ip)) ? null : String.valueOf(ip),
              device == null ? null : String.valueOf(device));
        });
  }

  // ---------------------------------------------------------------- helpers

  /** A personal account (not a pharmacy's staff login). */
  private User personal(UUID userUid) {
    User user =
        users
            .findByUid(userUid)
            .orElseThrow(() -> Refusal.notFound("user_not_found", "No such user."));
    if (user.isManaged()) {
      throw Refusal.invalid(
          "managed_login",
          "This is a pharmacy staff login: its pharmacy keeps its details and resets it.",
          "user");
    }
    return user;
  }

  /** The password again, and a second-factor code when two-factor is on. */
  private static boolean recentlyVerifiedPhone(User user, Instant now) {
    return user.getPhoneVerifiedAt() != null
        && user.getPhoneVerifiedAt().isAfter(now.minus(PHONE_RESET_FRESHNESS));
  }

  @Override
  public void confirmIdentity(
      UUID userUid, String token, String password, String code, ClientInfo client) {
    ClientInfo from = client == null ? ClientInfo.UNKNOWN : client;
    Instant now = clock.instant();
    User user =
        users
            .findByUid(userUid)
            .filter(User::isActive)
            .orElseThrow(() -> Refusal.unauthenticated("unauthenticated", "Sign in again."));
    guard.checkAccount(user, user.getEmail(), now);
    boolean passwordRight = passwords.matches(password, user.getPasswordHash());
    boolean codeRight =
        passwordRight && (!user.isTwoFactorEnabled() || twoFactor.confirmSecondFactor(user, code));
    if (!passwordRight || !codeRight) {
      // A wrong password here counts like one at sign-in, so a stolen session cannot be used to
      // guess the password or the code without end (AUTH-5).
      boolean locked =
          authentication.recordFailure(
              user, user.getEmail(), from, now, passwordRight ? "two_factor" : "password");
      if (locked) {
        throw guard.locked(guard.lockFor());
      }
      throw passwordRight ? invalidCode() : invalidPassword();
    }
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () -> {
          writes.executeWithoutResult(
              status -> {
                sessions
                    .findByTokenHash(Tokens.hash(token == null ? "" : token.trim()))
                    .filter(session -> session.getUserUid().equals(userUid) && session.liveAt(now))
                    .orElseThrow(() -> Refusal.unauthenticated("unauthenticated", "Sign in again."))
                    .reverified(now, user.isTwoFactorEnabled());
                audit.record(
                    "IDENTITY_CONFIRMED",
                    "User",
                    userUid,
                    null,
                    Map.of(
                        "ip",
                        String.valueOf(from.ip()),
                        "secondFactor",
                        user.isTwoFactorEnabled()));
              });
          return null;
        });
  }

  private void reauthenticate(User user, String password, String code) {
    if (!passwords.matches(password, user.getPasswordHash())) {
      throw invalidPassword();
    }
    if (user.isTwoFactorEnabled() && !twoFactor.confirmSecondFactor(user, code)) {
      throw invalidCode();
    }
  }

  /** Sends a fresh code by SMS; 429 too_many_codes past {@link #CODES_PER_HOUR} an hour. */
  private void send(User user, String purpose, String number, ClientInfo client) {
    Instant now = clock.instant();
    long recent =
        codes.countByUserUidAndPurposeAndCreatedAtAfter(
            user.getUid(), purpose, now.minus(Duration.ofHours(1)));
    if (recent >= CODES_PER_HOUR) {
      throw Refusal.tooManyRequests("Too many codes were sent; try again in an hour.", 3600);
    }
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    codes.save(
        new PhoneCode(
            user.getUid(),
            purpose,
            number,
            secrets.codeHash(SMS + code),
            now.plus(Duration.ofMinutes(CODE_MINUTES)),
            ip(client)));
    audit.record(
        VERIFY.equals(purpose) ? "PHONE_CODE_SENT" : "PASSWORD_RESET_REQUESTED",
        "User",
        user.getUid(),
        null,
        Map.of("phone", mask(number), "ip", String.valueOf(ip(client))));
    notices.ifAvailable(
        notice ->
            notice.verificationCode(user.getUid(), user.getName(), number, code, CODE_MINUTES));
  }

  /**
   * Earlier open changes and recoveries end: only the latest request counts. A revert link is left
   * alone: whoever moved the account must not be able to take the old address's way back.
   */
  private void closeOpen(UUID userUid, Instant now) {
    for (EmailChange change : changes.findByUserUidOrderByCreatedAtDesc(userUid)) {
      if (change.openAt(now) && !change.isRevert()) {
        change.cancel(now);
      }
    }
  }

  /** Domain separation of the SMS codes' keyed hashes from the recovery codes'. */
  private static final String SMS = "sms:";

  /** Whether a stored code hash is that of {@code code}: keyed, or the SHA-256 kept before. */
  private java.util.function.Predicate<String> smsCode(String code) {
    String typed = digits(code);
    String legacy = Tokens.hash(typed);
    return stored -> secrets.codeMatches(stored, SMS + typed, legacy);
  }

  private static String digits(String code) {
    return code == null ? "" : code.replaceAll("\\D", "");
  }

  private static String ip(ClientInfo client) {
    return client == null ? null : SessionIssuer.trim(client.ip(), 64);
  }

  /** {@code am***@example.com}, {@code +255754***111}: enough to recognise, not to read off. */
  static String mask(String value) {
    if (value == null) {
      return null;
    }
    int at = value.indexOf('@');
    if (at > 0) {
      return value.substring(0, Math.min(2, at)) + "***" + value.substring(at);
    }
    if (value.length() > 7) {
      return value.substring(0, value.length() - 6) + "***" + value.substring(value.length() - 3);
    }
    return "***";
  }

  private static Refusal invalidToken() {
    return Refusal.invalid(
        "invalid_token", "This link is not valid any more; ask for a new one.", "token");
  }

  private static Refusal invalidCode() {
    return Refusal.invalid("invalid_code", "The code is wrong or has expired.", "code");
  }

  private static Refusal invalidPassword() {
    // 422, not 401: a wrong password here must not end the session it is typed in.
    return Refusal.invalid("invalid_password", "The password is wrong.", "password");
  }
}
