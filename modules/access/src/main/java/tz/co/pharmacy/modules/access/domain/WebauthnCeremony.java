package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.MutableEntity;
import tz.co.pharmacy.platform.persistence.audit.AuditIgnore;

/**
 * A WebAuthn ceremony in progress: the request options sent to the browser (with their challenge),
 * kept in the database so any node can finish it.
 */
@Getter
@Entity
@AuditIgnore
@Table(schema = "access", name = "webauthn_ceremonies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebauthnCeremony extends MutableEntity {

  public static final String REGISTER = "REGISTER";
  public static final String ASSERT = "ASSERT";

  private String kind;
  private UUID userUid;
  private String requestJson;
  private Instant expiresAt;
  private Instant consumedAt;

  public WebauthnCeremony(String kind, UUID userUid, String requestJson, Instant expiresAt) {
    this.kind = kind;
    this.userUid = userUid;
    this.requestJson = requestJson;
    this.expiresAt = expiresAt;
  }

  public boolean openAt(Instant at) {
    return consumedAt == null && expiresAt.isAfter(at);
  }

  public void consume(Instant at) {
    consumedAt = at;
  }
}
