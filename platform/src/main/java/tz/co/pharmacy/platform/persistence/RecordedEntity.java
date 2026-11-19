package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Uids;

/**
 * The columns every entity carries: an internal surrogate key that never leaves the module; the
 * record's uid, a UUIDv7 that identifies it in the API, in events and in other modules; and who
 * created it and when.
 */
@Getter
@MappedSuperclass
public abstract class RecordedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false, unique = true)
  private UUID uid = Uids.newUid();

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false, updatable = false)
  private String createdBy;

  @PrePersist
  void recordCreation() {
    createdAt = Clocks.now();
    createdBy = ExecutionContext.actor().reference();
  }
}
