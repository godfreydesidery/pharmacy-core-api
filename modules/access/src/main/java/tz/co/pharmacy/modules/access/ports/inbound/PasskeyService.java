package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * Passkeys (WebAuthn, discoverable credentials): registering one on the signed-in account, and
 * signing in with one without typing an email. A passkey sign-in satisfies two-factor.
 */
public interface PasskeyService {

  PasskeyOptions startRegistration(UUID userUid);

  /** 422 passkey_rejected; 409 passkey_exists; 401 ceremony_expired. */
  PasskeyView finishRegistration(
      UUID userUid, UUID ceremonyUid, String credentialJson, String name);

  PasskeyOptions startSignIn();

  /** 401 passkey_rejected; 401 ceremony_expired; 423 account_locked. */
  SignInResult finishSignIn(UUID ceremonyUid, String credentialJson, ClientInfo client);

  List<PasskeyView> list(UUID userUid);

  /** 404 passkey_not_found. */
  void remove(UUID userUid, UUID passkeyUid);
}
