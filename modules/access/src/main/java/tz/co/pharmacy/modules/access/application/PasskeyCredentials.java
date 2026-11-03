package tz.co.pharmacy.modules.access.application;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.exception.Base64UrlException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.domain.Passkey;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.PasskeyRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;

/**
 * The relying party's view of stored passkeys. The WebAuthn "username" is the user's email; the
 * user handle is the random handle assigned with their first passkey. Called inside the passkey
 * service's transactions.
 */
@Component
class PasskeyCredentials implements CredentialRepository {

  private final UserRepository users;
  private final PasskeyRepository passkeys;

  PasskeyCredentials(UserRepository users, PasskeyRepository passkeys) {
    this.users = users;
    this.passkeys = passkeys;
  }

  @Override
  public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
    return users
        .findByEmail(username)
        .map(
            user ->
                passkeys.findAllByUserUidOrderByIdDesc(user.getUid()).stream()
                    .map(
                        passkey ->
                            PublicKeyCredentialDescriptor.builder()
                                .id(bytes(passkey.getCredentialId()))
                                .build())
                    .collect(Collectors.toSet()))
        .orElse(Set.of());
  }

  @Override
  public Optional<ByteArray> getUserHandleForUsername(String username) {
    return users
        .findByEmail(username)
        .map(User::getWebauthnUserHandle)
        .filter(handle -> handle != null && !handle.isBlank())
        .map(PasskeyCredentials::bytes);
  }

  @Override
  public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
    return passkeys.findAllByUserHandle(userHandle.getBase64Url()).stream()
        .findFirst()
        .flatMap(passkey -> users.findByUid(passkey.getUserUid()))
        .map(User::getEmail);
  }

  @Override
  public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
    return passkeys
        .findByCredentialId(credentialId.getBase64Url())
        .filter(passkey -> passkey.getUserHandle().equals(userHandle.getBase64Url()))
        .map(PasskeyCredentials::registered);
  }

  @Override
  public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
    return passkeys
        .findByCredentialId(credentialId.getBase64Url())
        .map(PasskeyCredentials::registered)
        .map(Set::of)
        .orElse(Set.of());
  }

  private static RegisteredCredential registered(Passkey passkey) {
    return RegisteredCredential.builder()
        .credentialId(bytes(passkey.getCredentialId()))
        .userHandle(bytes(passkey.getUserHandle()))
        .publicKeyCose(bytes(passkey.getPublicKeyCose()))
        .signatureCount(passkey.getSignatureCount())
        .build();
  }

  static ByteArray bytes(String base64Url) {
    try {
      return ByteArray.fromBase64Url(base64Url);
    } catch (Base64UrlException corrupt) {
      throw new IllegalStateException("corrupt base64url in access.passkeys", corrupt);
    }
  }
}
