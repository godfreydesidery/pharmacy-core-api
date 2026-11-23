package tz.co.pharmacy.modules.integrations.ports.outbound;

/**
 * Seals short secrets at rest with the platform keyring, under the {@code webhooks} purpose's
 * active key. Webhook signing secrets are kept sealed.
 */
public interface SecretSealing {

  String seal(String plain);

  /**
   * Opens a sealed value. Throws {@link IllegalStateException} when no key in the keyring opens it
   * (its key was removed, or the key material changed): the secret must be replaced.
   */
  String open(String sealed);
}
