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
  WEBHOOKS("webhooks");

  private final String id;

  Purpose(String id) {
    this.id = id;
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
