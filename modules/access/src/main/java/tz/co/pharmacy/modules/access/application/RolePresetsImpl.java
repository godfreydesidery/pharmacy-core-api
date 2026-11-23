package tz.co.pharmacy.modules.access.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.domain.Membership;
import tz.co.pharmacy.modules.access.domain.Role;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.RoleRepository;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.RolePresets;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Uids;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * The one-time "Apply recommended roles" notice. The admin's decision is kept per pharmacy and
 * presets version in {@code access.role_preset_decisions} (forced row-level security), written with
 * an upsert so two instances deciding at once leave one row.
 */
@Service
class RolePresetsImpl implements RolePresets {

  static final String APPLIED = "APPLIED";
  static final String DISMISSED = "DISMISSED";

  private static final List<String> ORDER = List.copyOf(Permissions.TENANT_ALL);

  private final RoleRepository roles;
  private final MembershipRepository memberships;
  private final AuditLog audit;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  RolePresetsImpl(
      RoleRepository roles,
      MembershipRepository memberships,
      AuditLog audit,
      DataSource dataSource,
      Clock clock) {
    this.roles = roles;
    this.memberships = memberships;
    this.audit = audit;
    this.jdbc = new JdbcTemplate(dataSource);
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Recommendations recommendations() {
    return current();
  }

  @Override
  @Transactional
  public Recommendations apply() {
    UUID tenant = TenantContext.requireTenant();
    List<Role> builtIn = builtIn(tenant);
    Map<UUID, Set<String>> target = new HashMap<>();
    for (Role role : builtIn) {
      Set<String> preset = Permissions.preset(role.getCode());
      if (!preset.equals(Set.copyOf(role.getPermissions()))) {
        target.put(role.getUid(), preset);
        if (!"ADMIN".equals(role.getCode())) {
          // The Administrator role always has everything (Batch 2); any other role may gain only
          // what the caller holds (AUTH-15).
          UserAdministrationImpl.requireGrantable(added(role.getPermissions(), preset));
        }
      }
    }
    if (target.isEmpty()) {
      decide(APPLIED);
      return current();
    }
    boolean someoneManagesPeople =
        memberships.findAll().stream()
            .filter(member -> tenant.equals(member.getTenantUid()))
            .filter(Membership::isActive)
            .anyMatch(
                member ->
                    target
                        .getOrDefault(member.getRole().getUid(), member.getRole().getPermissions())
                        .contains(Permissions.USERS_MANAGE));
    if (!someoneManagesPeople) {
      throw UserAdministrationImpl.lastPeopleManager();
    }
    for (Role role : builtIn) {
      Set<String> after = target.get(role.getUid());
      if (after == null) {
        continue;
      }
      audit.record(
          "ROLE_PERMISSIONS_CHANGED",
          "Role",
          role.getUid(),
          Map.of("code", role.getCode(), "permissions", ordered(role.getPermissions())),
          Map.of(
              "code",
              role.getCode(),
              "permissions",
              ordered(after),
              "reason",
              "Recommended roles applied (" + Permissions.PRESETS_VERSION + ")"));
      role.change(role.getName(), role.getDescription(), after);
    }
    roles.flush();
    decide(APPLIED);
    return current();
  }

  @Override
  @Transactional
  public Recommendations dismiss() {
    decide(DISMISSED);
    return current();
  }

  private Recommendations current() {
    UUID tenant = TenantContext.requireTenant();
    List<RoleChange> changes = new ArrayList<>();
    for (Role role : builtIn(tenant)) {
      Set<String> preset = Permissions.preset(role.getCode());
      List<String> add = added(role.getPermissions(), preset);
      List<String> remove = added(preset, role.getPermissions());
      if (!add.isEmpty() || !remove.isEmpty()) {
        changes.add(new RoleChange(role.getUid(), role.getCode(), role.getName(), add, remove));
      }
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select decision, coalesce(updated_at, created_at) as decided_at,"
                + " coalesce(updated_by, created_by) as decided_by"
                + " from access.role_preset_decisions"
                + " where tenant_uid = ? and presets_version = ?",
            tenant,
            Permissions.PRESETS_VERSION);
    String decision = rows.isEmpty() ? null : (String) rows.getFirst().get("decision");
    Instant decidedAt =
        rows.isEmpty() ? null : ((Timestamp) rows.getFirst().get("decided_at")).toInstant();
    String decidedBy = rows.isEmpty() ? null : (String) rows.getFirst().get("decided_by");
    return new Recommendations(
        Permissions.PRESETS_VERSION,
        decision == null && !changes.isEmpty(),
        DISMISSED.equals(decision),
        decision,
        decidedAt,
        decidedBy,
        List.copyOf(changes));
  }

  private void decide(String decision) {
    UUID tenant = TenantContext.requireTenant();
    Timestamp now = Timestamp.from(clock.instant());
    var actor = ExecutionContext.actor();
    jdbc.update(
        "insert into access.role_preset_decisions (uid, tenant_uid, presets_version, decision,"
            + " created_at, created_by, created_by_uid) values (?, ?, ?, ?, ?, ?, ?)"
            + " on conflict (tenant_uid, presets_version) do update set decision ="
            + " excluded.decision, updated_at = excluded.created_at, updated_by ="
            + " excluded.created_by, updated_by_uid = excluded.created_by_uid,"
            + " version = access.role_preset_decisions.version + 1",
        Uids.newUid(),
        tenant,
        Permissions.PRESETS_VERSION,
        decision,
        now,
        actor.reference(),
        actor.uid());
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("presetsVersion", Permissions.PRESETS_VERSION);
    details.put("decision", decision);
    audit.record(
        APPLIED.equals(decision) ? "ROLE_PRESETS_APPLIED" : "ROLE_PRESETS_DISMISSED",
        "Tenant",
        tenant,
        null,
        details);
  }

  /** This pharmacy's built-in roles, in the presets' order. */
  private List<Role> builtIn(UUID tenant) {
    List<String> codes = List.copyOf(Permissions.defaultRoles().keySet());
    return roles.findAllByOrderByNameAsc().stream()
        .filter(role -> tenant.equals(role.getTenantUid()))
        .filter(Role::isSystem)
        .filter(role -> codes.contains(role.getCode()))
        .sorted(Comparator.comparingInt(role -> codes.indexOf(role.getCode())))
        .toList();
  }

  /** What {@code to} has that {@code from} lacks, in catalogue order. */
  private static List<String> added(Set<String> from, Set<String> to) {
    Set<String> difference = new java.util.LinkedHashSet<>(to);
    difference.removeAll(from);
    return ordered(difference);
  }

  private static List<String> ordered(Set<String> codes) {
    List<String> result = new ArrayList<>(ORDER.stream().filter(codes::contains).toList());
    codes.stream().filter(code -> !ORDER.contains(code)).sorted().forEach(result::add);
    return result;
  }
}
