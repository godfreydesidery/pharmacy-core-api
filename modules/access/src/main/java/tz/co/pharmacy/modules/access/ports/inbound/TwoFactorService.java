package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

/** TOTP two-factor (RFC 6238: SHA-1, 30 seconds, 6 digits) and single-use recovery codes. */
public interface TwoFactorService {

  SecurityOverview overview(UUID userUid);

  /** A new secret to scan; 409 two_factor_already_enabled. */
  TwoFactorSetup begin(UUID userUid);

  /**
   * Turns two-factor on with a code from the new secret and returns 10 recovery codes, shown once.
   * 422 two_factor_not_started; 422 invalid_code.
   */
  List<String> enable(UUID userUid, String code);

  /**
   * The same, on session {@code sessionUid}: the code just proved the second factor, so that
   * session counts as two-factor from now on (a pharmacy that requires it lets it in).
   */
  default List<String> enable(UUID userUid, String code, UUID sessionUid) {
    return enable(userUid, code);
  }

  /** Turns two-factor off with a current code or a recovery code; 422 invalid_code. */
  void disable(UUID userUid, String code);

  /** Replaces the recovery codes, with a current code; 422 invalid_code. */
  List<String> regenerateRecoveryCodes(UUID userUid, String code);

  /**
   * Answers a sign-in challenge with a TOTP code or a recovery code. 401 challenge_expired after 5
   * minutes or 5 wrong codes; 401 invalid_code.
   */
  SignInResult verify(String challengeToken, String code, ClientInfo client);
}
