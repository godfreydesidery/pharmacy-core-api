package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.List;

/**
 * What a sign-in step produced: a session, or a challenge for the second factor, answered with
 * {@link TwoFactorService#verify}. {@code methods} are TOTP, RECOVERY_CODE and, when the user has
 * one, PASSKEY.
 */
public sealed interface SignInOutcome {

  record Signed(SignInResult session) implements SignInOutcome {}

  record ChallengeRequired(String challengeToken, List<String> methods, Instant expiresAt)
      implements SignInOutcome {}
}
