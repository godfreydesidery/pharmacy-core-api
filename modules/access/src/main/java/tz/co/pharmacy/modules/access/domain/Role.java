package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A tenant's role: a named set of permissions. System roles are seeded with every tenant. */
@Getter
@Entity
@Table(schema = "access", name = "roles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role extends TenantEntity {

  private String code;
  private String name;
  private String description;
  private boolean system;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      schema = "access",
      name = "role_permissions",
      joinColumns = @JoinColumn(name = "role_id"))
  @Column(name = "permission")
  private Set<String> permissions = new LinkedHashSet<>();

  public Role(
      String code,
      String name,
      String description,
      boolean system,
      Collection<String> permissions) {
    this.code = code;
    this.name = name;
    this.description = description;
    this.system = system;
    this.permissions = new LinkedHashSet<>(permissions);
  }

  public void change(String name, String description, Collection<String> permissions) {
    if (!system) {
      this.name = name;
    }
    this.description = description;
    this.permissions.clear();
    this.permissions.addAll(permissions);
  }
}
