package tz.co.pharmacy.modules.notifications.ports.inbound;

/**
 * The sealing the message providers' secrets use (AES-256-GCM under a key derived from {@code
 * pharmacy.access.encryption-key}), offered to other modules that keep a short secret at rest, such
 * as webhook signing secrets, so there is one sealing and one key.
 */
public interface Sealing {

  String seal(String plain);

  /** Throws {@link IllegalStateException} when the value was sealed under another key. */
  String open(String sealed);
}
