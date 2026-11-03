package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import tz.co.pharmacy.platform.kernel.ExecutionContext;

/**
 * An entity whose rows change: it records who changed it last and when, and carries a version for
 * optimistic locking (a stale write is refused with 409 stale_version).
 */
@Getter
@MappedSuperclass
public abstract class MutableEntity extends RecordedEntity {

  private Instant updatedAt;
  private String updatedBy;

  @Version private long version;

  @PreUpdate
  void recordUpdate() {
    updatedAt = Instant.now();
    updatedBy = ExecutionContext.actor().reference();
  }
}
