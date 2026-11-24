package tz.co.pharmacy.modules.access.application;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.Passkey;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.domain.WebauthnCeremony;
import tz.co.pharmacy.modules.access.persistence.PasskeyRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.persistence.WebauthnCeremonyRepository;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.PasskeyOptions;
import tz.co.pharmacy.modules.access.ports.inbound.PasskeyService;
import tz.co.pharmacy.modules.access.ports.inbound.PasskeyView;
import tz.co.pharmacy.modules.access.ports.inbound.SignInResult;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Passkeys through the Yubico relying party. Each ceremony's options (with their challenge) are
 * kept in the database for two minutes, so any node can finish it. Registration requires a
 * discoverable credential, so sign-in needs no email: the authenticator names the account.
 */
@Service
class PasskeyServiceImpl implements PasskeyService {

  private static final Logger log = LoggerFactory.getLogger(PasskeyServiceImpl.class);
  private static final Duration CEREMONY = Duration.ofMinutes(2);
  private static final long TIMEOUT_MILLIS = 120_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final RelyingParty relyingParty;
  private final UserRepository users;
  private final PasskeyRepository passkeys;
  private final WebauthnCeremonyRepository ceremonies;
  private final SessionIssuer issuer;
  private final SignInGuard guard;
  private final AuditLog audit;
  private final TransactionTemplate writes;

  private final Clock clock;
  private final org.springframework.beans.factory.ObjectProvider<
          tz.co.pharmacy.modules.access.ports.outbound.AccessNotices>
      notices;

  PasskeyServiceImpl(
      RelyingParty relyingParty,
      UserRepository users,
      PasskeyRepository passkeys,
      WebauthnCeremonyRepository ceremonies,
      SessionIssuer issuer,
      SignInGuard guard,
      AuditLog audit,
      PlatformTransactionManager transactionManager,
      org.springframework.beans.factory.ObjectProvider<
              tz.co.pharmacy.modules.access.ports.outbound.AccessNotices>
          notices,
      Clock clock) {
    this.notices = notices;
    this.clock = clock;
    this.relyingParty = relyingParty;
    this.users = users;
    this.passkeys = passkeys;
    this.ceremonies = ceremonies;
    this.issuer = issuer;
    this.guard = guard;
    this.audit = audit;
    this.writes = new TransactionTemplate(transactionManager);
  }

  @Override
  @Transactional
  public PasskeyOptions startRegistration(UUID userUid) {
    User user = user(userUid);
    if (user.getWebauthnUserHandle() == null) {
      byte[] handle = new byte[32];
      RANDOM.nextBytes(handle);
      user.assignWebauthnUserHandle(new ByteArray(handle).getBase64Url());
    }
    PublicKeyCredentialCreationOptions options =
        relyingParty.startRegistration(
            StartRegistrationOptions.builder()
                .user(
                    UserIdentity.builder()
                        .name(user.getEmail())
                        .displayName(user.getName())
                        .id(PasskeyCredentials.bytes(user.getWebauthnUserHandle()))
                        .build())
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build())
                .timeout(TIMEOUT_MILLIS)
                .build());
    try {
      WebauthnCeremony ceremony =
          ceremonies.save(
              new WebauthnCeremony(
                  WebauthnCeremony.REGISTER,
                  userUid,
                  options.toJson(),
                  clock.instant().plus(CEREMONY)));
      return new PasskeyOptions(ceremony.getUid(), options.toCredentialsCreateJson());
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Override
  @Transactional
  public PasskeyView finishRegistration(
      UUID userUid, UUID ceremonyUid, String credentialJson, String name) {
    Instant now = clock.instant();
    WebauthnCeremony ceremony = ceremony(ceremonyUid, WebauthnCeremony.REGISTER, now);
    if (!userUid.equals(ceremony.getUserUid())) {
      throw expired();
    }
    ceremony.consume(now);
    RegistrationResult result;
    PublicKeyCredentialCreationOptions request;
    try {
      request = PublicKeyCredentialCreationOptions.fromJson(ceremony.getRequestJson());
      result =
          relyingParty.finishRegistration(
              FinishRegistrationOptions.builder()
                  .request(request)
                  .response(PublicKeyCredential.parseRegistrationResponseJson(credentialJson))
                  .build());
    } catch (Exception rejected) {
      log.info("Passkey registration rejected: {}", rejected.getMessage());
      throw Refusal.invalid("passkey_rejected", "The passkey could not be registered.");
    }
    String credentialId = result.getKeyId().getId().getBase64Url();
    if (passkeys.findByCredentialId(credentialId).isPresent()) {
      throw Refusal.conflict("passkey_exists", "This passkey is already registered.");
    }
    String transports =
        result
            .getKeyId()
            .getTransports()
            .map(
                set ->
                    set.stream()
                        .map(AuthenticatorTransport::getId)
                        .collect(Collectors.joining(",")))
            .orElse(null);
    String label =
        name == null || name.isBlank()
            ? "Passkey " + (passkeys.countByUserUid(userUid) + 1)
            : Checks.text(name, "name", 60);
    Passkey passkey =
        passkeys.save(
            new Passkey(
                userUid,
                credentialId,
                request.getUser().getId().getBase64Url(),
                result.getPublicKeyCose().getBase64Url(),
                result.getSignatureCount(),
                label,
                transports));
    audit.record("PASSKEY_REGISTERED", "User", userUid, null, Map.of("name", label));
    User owner = user(userUid);
    String phone = owner.hasVerifiedPhone() ? owner.getPhone() : null;
    notices.ifAvailable(
        notice ->
            notice.securityChanged(
                owner.getUid(),
                owner.getName(),
                owner.mailbox(),
                phone,
                tz.co.pharmacy.modules.access.ports.outbound.AccessNotices.SecurityChange
                    .PASSKEY_ADDED,
                clock.instant()));
    return view(passkey);
  }

  @Override
  public PasskeyOptions startSignIn() {
    AssertionRequest request =
        relyingParty.startAssertion(
            StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(TIMEOUT_MILLIS)
                .build());
    return ExecutionContext.callAs(
        SessionIssuer.serviceFrame(),
        () ->
            writes.execute(
                status -> {
                  try {
                    WebauthnCeremony ceremony =
                        ceremonies.save(
                            new WebauthnCeremony(
                                WebauthnCeremony.ASSERT,
                                null,
                                request.toJson(),
                                clock.instant().plus(CEREMONY)));
                    return new PasskeyOptions(ceremony.getUid(), request.toCredentialsGetJson());
                  } catch (java.io.IOException impossible) {
                    throw new IllegalStateException(impossible);
                  }
                }));
  }

  @Override
  public SignInResult finishSignIn(UUID ceremonyUid, String credentialJson, ClientInfo client) {
    ClientInfo from = client == null ? ClientInfo.UNKNOWN : client;
    Instant now = clock.instant();
    guard.checkClient(from.ip(), now);
    User user =
        ExecutionContext.callAs(
            SessionIssuer.serviceFrame(),
            () ->
                writes.execute(
                    status -> {
                      WebauthnCeremony ceremony =
                          ceremony(ceremonyUid, WebauthnCeremony.ASSERT, now);
                      ceremony.consume(now);
                      AssertionResult result;
                      try {
                        result =
                            relyingParty.finishAssertion(
                                FinishAssertionOptions.builder()
                                    .request(AssertionRequest.fromJson(ceremony.getRequestJson()))
                                    .response(
                                        PublicKeyCredential.parseAssertionResponseJson(
                                            credentialJson))
                                    .build());
                      } catch (Exception rejected) {
                        log.info("Passkey sign-in rejected: {}", rejected.getMessage());
                        throw rejected();
                      }
                      // A passkey stands for two factors only when the device checked the
                      // person (PIN, fingerprint, face): one without user verification is refused
                      // (adversarial review AUTH-10; WebAuthn Level 3 §6.2.3).
                      if (!result.isSuccess() || !result.isUserVerified()) {
                        throw rejected();
                      }
                      Passkey passkey =
                          passkeys
                              .findByCredentialId(
                                  result.getCredential().getCredentialId().getBase64Url())
                              .orElseThrow(PasskeyServiceImpl::rejected);
                      passkey.used(result.getSignatureCount(), now);
                      return users
                          .findByUid(passkey.getUserUid())
                          .orElseThrow(PasskeyServiceImpl::rejected);
                    }));
    if (!user.isActive()) {
      throw rejected();
    }
    guard.checkAccount(user, user.getEmail(), now);
    return issuer.issue(user, from, "PASSKEY");
  }

  @Override
  @Transactional(readOnly = true)
  public List<PasskeyView> list(UUID userUid) {
    return passkeys.findAllByUserUidOrderByIdDesc(userUid).stream()
        .map(PasskeyServiceImpl::view)
        .toList();
  }

  @Override
  @Transactional
  public void remove(UUID userUid, UUID passkeyUid) {
    Passkey passkey =
        passkeys
            .findByUidAndUserUid(passkeyUid, userUid)
            .orElseThrow(() -> Refusal.notFound("passkey_not_found", "No such passkey."));
    User owner = user(userUid);
    if (!owner.isTwoFactorEnabled() && passkeys.countByUserUid(userUid) <= 1) {
      // The last second factor: the password would then stand alone (STD-21).
      SecondFactors.passwordMayStandAlone(owner);
    }
    passkeys.delete(passkey);
    audit.record("PASSKEY_REMOVED", "User", userUid, Map.of("name", passkey.getName()), null);
  }

  private WebauthnCeremony ceremony(UUID ceremonyUid, String kind, Instant at) {
    if (ceremonyUid == null) {
      throw expired();
    }
    return ceremonies
        .findByUid(ceremonyUid)
        .filter(found -> found.getKind().equals(kind) && found.openAt(at))
        .orElseThrow(PasskeyServiceImpl::expired);
  }

  private User user(UUID userUid) {
    return users
        .findByUid(userUid)
        .orElseThrow(() -> Refusal.notFound("user_not_found", "No such user."));
  }

  private static PasskeyView view(Passkey passkey) {
    return new PasskeyView(
        passkey.getUid(),
        passkey.getName(),
        passkey.getTransports(),
        passkey.getCreatedAt(),
        passkey.getLastUsedAt());
  }

  private static Refusal rejected() {
    return Refusal.unauthenticated("passkey_rejected", "That passkey was not accepted.");
  }

  private static Refusal expired() {
    return Refusal.unauthenticated(
        "ceremony_expired", "The passkey request has expired; try again.");
  }
}
