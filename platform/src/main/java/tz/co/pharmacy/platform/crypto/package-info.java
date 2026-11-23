/**
 * Secrets kept at rest: the keyring of named AES-256-GCM keys, the active key of each purpose (TOTP
 * seeds, message provider secrets, webhook signing secrets), the sealed columns the modules
 * declare, and the re-seal that moves them to the active key so an old key can be removed.
 */
@ApplicationModule(displayName = "platform-crypto", type = ApplicationModule.Type.OPEN)
package tz.co.pharmacy.platform.crypto;

import org.springframework.modulith.ApplicationModule;
