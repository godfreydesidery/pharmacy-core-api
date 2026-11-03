package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * An invitation to join the tenant with a role and branch access. The invitee proves it with the
 * token sent by email (only its hash is stored); accepting it makes or reuses the user and gives
 * them the membership.
 */
@Getter
@Entity
@Table(schema = "access", name = "invitations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation extends TenantEntity {

  private String tokenHash;
  private String email;
  private String name;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "role_id")
  private Role role;

  private boolean allBranches;

  /** The branch uids, comma-separated; empty when {@code allBranches}. */
  private String branchUids;

  private String invitedBy;
  private Instant expiresAt;
  private Instant acceptedAt;
  private Instant revokedAt;

  public Invitation(
      String tokenHash,
      String email,
      String name,
      Role role,
      boolean allBranches,
      Collection<UUID> branchUids,
      String invitedBy,
      Instant expiresAt) {
    this.tokenHash = tokenHash;
    this.email = email;
    this.name = name;
    this.role = role;
    this.allBranches = allBranches;
    this.branchUids =
        allBranches || branchUids == null
            ? ""
            : branchUids.stream().map(UUID::toString).collect(Collectors.joining(","));
    this.invitedBy = invitedBy;
    this.expiresAt = expiresAt;
  }

  public List<UUID> branchUidList() {
    if (branchUids == null || branchUids.isBlank()) {
      return List.of();
    }
    return Arrays.stream(branchUids.split(",")).map(UUID::fromString).toList();
  }

  public String status(Instant at) {
    if (revokedAt != null) {
      return "REVOKED";
    }
    if (acceptedAt != null) {
      return "ACCEPTED";
    }
    return expiresAt.isAfter(at) ? "PENDING" : "EXPIRED";
  }

  public boolean openAt(Instant at) {
    return "PENDING".equals(status(at));
  }

  public void accept(Instant at) {
    acceptedAt = at;
  }

  public void revoke(Instant at) {
    if (revokedAt == null && acceptedAt == null) {
      revokedAt = at;
    }
  }
}
