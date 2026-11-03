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

/** A single-use two-factor recovery code, stored as its SHA-256 hash. */
@Getter
@Entity
@AuditIgnore
@Table(schema = "access", name = "recovery_codes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecoveryCode extends MutableEntity {

  private UUID userUid;
  private String codeHash;
  private Instant usedAt;

  public RecoveryCode(UUID userUid, String codeHash) {
    this.userUid = userUid;
    this.codeHash = codeHash;
  }

  public void use(Instant at) {
    usedAt = at;
  }
}
