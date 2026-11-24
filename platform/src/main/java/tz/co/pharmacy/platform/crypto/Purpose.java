package tz.co.pharmacy.platform.crypto;

import java.util.Locale;

/**
 * What a sealed value is for. Each purpose has its own active key ({@code pharmacy.sealing.<id>}),
 * so a key that leaks or must change reaches one kind of secret only, and a versioned value is
 * bound to its purpose (AES-GCM associated data): a value sealed for one purpose does not open as
 * another.
 */
public enum Purpose {
  /** TOTP seeds of authenticator apps (access), and the short-lived live-stream tickets. */
  TOTP("totp"),
  /** Message provider secrets: API keys, auth tokens, SMTP passwords (notifications). */
  PROVIDERS("providers"),
  /** Webhook signing secrets (integrations). */
  WEBHOOKS("webhooks"),
  /**
   * The pepper of short codes kept as keyed hashes (HMAC-SHA-256): two-factor recovery codes and
   * SMS codes (access; adversarial review AUTH-16). Unset, it uses the key {@link #TOTP} seals
   * with, so a server needs no new secret; each hash names its key, so a later change keeps them
   * working.
   */
  CODES("codes", TOTP),
  /**
   * Account messages (reset, invitation and recovery links, codes) while they wait to be sent
   * (notifications; AUTH-8). Unset, it uses the key {@link #PROVIDERS} seals with.
   */
  MESSAGES("messages", PROVIDERS);

  private final String id;
  private final Purpose fallback;

  Purpose(String id) {
    this(id, null);
  }

  Purpose(String id, Purpose fallback) {
    this.id = id;
    this.fallback = fallback;
  }

  /**
   * The purpose whose active key this one uses while {@code pharmacy.sealing.<id>} is unset; null:
   * the legacy key.
   */
  public Purpose fallback() {
    return fallback;
  }

  /** The purpose's name in configuration and status: {@code totp}, {@code providers}, ... */
  public String id() {
    return id;
  }

  /** The property naming the purpose's active key id. */
  public String property() {
    return "pharmacy.sealing." + id;
  }

  /** The environment variable of {@link #property()}. */
  public String variable() {
    return "PHARMACY_SEALING_" + id.toUpperCase(Locale.ROOT);
  }
}
