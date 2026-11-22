package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * A user's place in one tenant: one role, and either every branch or the listed ones. A user may
 * read their own memberships in every tenant (to choose a workspace); a tenant sees its own.
 *
 * <p>It also holds what the pharmacy may know of the person beyond its walls: when they last worked
 * here ({@code lastActiveAt}, not when they last signed in anywhere), and, once they leave, their
 * name, email and phone as they were then ({@code former*}), so a former employer does not see what
 * the person changes afterwards.
 */
@Getter
@Entity
@Table(schema = "access", name = "memberships")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership extends TenantEntity {

  private UUID userUid;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "role_id")
  private Role role;

  private boolean allBranches;
  private boolean active;
  private Instant lastActiveAt;
  private String formerName;
  private String formerEmail;
  private String formerPhone;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      schema = "access",
      name = "membership_branches",
      joinColumns = @JoinColumn(name = "membership_id"))
  @Column(name = "branch_uid")
  private Set<UUID> branchUids = new LinkedHashSet<>();

  public Membership(UUID userUid, Role role, boolean allBranches, Collection<UUID> branchUids) {
    this.userUid = userUid;
    this.role = role;
    this.active = true;
    assign(role, allBranches, branchUids);
  }

  /** The person linked the staff login holding this membership to their personal account. */
  public void moveTo(UUID personalUid) {
    this.userUid = personalUid;
  }

  public void change(Role role, boolean allBranches, Collection<UUID> branchUids, boolean active) {
    assign(role, allBranches, branchUids);
    this.active = active;
    if (active) {
      // Back at work here: the pharmacy sees the person as they are now.
      formerName = null;
      formerEmail = null;
      formerPhone = null;
    }
  }

  /** The person left: what the pharmacy keeps of them from now on. */
  public void left(String name, String email, String phone) {
    formerName = name;
    formerEmail = email;
    formerPhone = phone;
  }

  /** Whether the pharmacy sees the person as they were when they left. */
  public boolean isFormer() {
    return !active && formerEmail != null;
  }

  public void workedAt(Instant at) {
    lastActiveAt = at;
  }

  private void assign(Role role, boolean allBranches, Collection<UUID> branchUids) {
    this.role = role;
    this.allBranches = allBranches;
    this.branchUids.clear();
    if (!allBranches && branchUids != null) {
      this.branchUids.addAll(branchUids);
    }
  }
}
