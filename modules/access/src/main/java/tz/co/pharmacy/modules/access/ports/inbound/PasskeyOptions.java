package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.UUID;

/**
 * The options of a WebAuthn ceremony as JSON, {@code {"publicKey": {...}}}, for the browser's
 * {@code PublicKeyCredential.parseCreationOptionsFromJSON} or {@code parseRequestOptionsFromJSON},
 * and the ceremony to finish.
 */
public record PasskeyOptions(UUID ceremonyUid, String optionsJson) {}
