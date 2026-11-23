package tz.co.pharmacy.modules.integrations.ports.outbound;

/**
 * Seals short secrets at rest with the platform key ({@code pharmacy.access.encryption-key}), the
 * same sealing the message providers' secrets use. Webhook signing secrets are kept sealed.
 */
public interface SecretSealing {

  String seal(String plain);

  /**
   * Opens a sealed value. Throws {@link IllegalStateException} when it was sealed under another key
   * (the server's key changed): the secret must be replaced.
   */
  String open(String sealed);
}
