package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.MutableEntity;

/** A WebAuthn credential (a passkey): its id and COSE public key in base64url. */
@Getter
@Entity
@Table(schema = "access", name = "passkeys")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Passkey extends MutableEntity {

  private UUID userUid;
  private String credentialId;
  private String userHandle;
  private String publicKeyCose;
  private long signatureCount;
  private String name;
  private String transports;
  private Instant lastUsedAt;

  public Passkey(
      UUID userUid,
      String credentialId,
      String userHandle,
      String publicKeyCose,
      long signatureCount,
      String name,
      String transports) {
    this.userUid = userUid;
    this.credentialId = credentialId;
    this.userHandle = userHandle;
    this.publicKeyCose = publicKeyCose;
    this.signatureCount = signatureCount;
    this.name = name;
    this.transports = transports;
  }

  public void used(long newSignatureCount, Instant at) {
    signatureCount = newSignatureCount;
    lastUsedAt = at;
  }
}
