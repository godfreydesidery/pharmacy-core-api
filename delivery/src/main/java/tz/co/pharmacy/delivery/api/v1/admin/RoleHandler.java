package tz.co.pharmacy.delivery.api.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.PermissionView;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.RoleCommand;
import tz.co.pharmacy.modules.access.ports.inbound.RolePresets;
import tz.co.pharmacy.modules.access.ports.inbound.RoleView;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.platform.kernel.Refusal;

/** The tenant's roles and the permission catalogue they draw on. */
@RestController
@RequestMapping("/api/v1")
public class RoleHandler {

  private final UserAdministration users;
  private final RolePresets presets;

  public RoleHandler(UserAdministration users, RolePresets presets) {
    this.users = users;
    this.presets = presets;
  }

  @GetMapping("/roles")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<List<RoleView>> roles() {
    return ApiResponse.ok(users.roles(), "Roles retrieved");
  }

  @PostMapping("/roles")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<RoleView> create(@RequestBody RoleRequest body) {
    if (body == null) {
      throw Refusal.malformed("A role is required.");
    }
    return ApiResponse.created(
        users.createRole(
            new RoleCommand(body.code(), body.name(), body.description(), body.permissions())),
        "Role created");
  }

  @PutMapping("/roles/{roleUid}")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<RoleView> change(@PathVariable UUID roleUid, @RequestBody RoleRequest body) {
    if (body == null) {
      throw Refusal.malformed("A role is required.");
    }
    return ApiResponse.ok(
        users.changeRole(
            roleUid,
            new RoleCommand(body.code(), body.name(), body.description(), body.permissions())),
        "Role changed");
  }

  /**
   * How the built-in roles differ from the recommended presets, and whether the admin has decided:
   * the web shows the one-time "Apply recommended roles" notice while {@code showNotice} is true.
   */
  @GetMapping("/roles/recommendations")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<RolePresets.Recommendations> recommendations() {
    return ApiResponse.ok(presets.recommendations(), "Role recommendations retrieved");
  }

  /** Sets every built-in role to its recommended permissions. */
  @PostMapping("/roles/recommendations/apply")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<RolePresets.Recommendations> applyRecommendations() {
    return ApiResponse.ok(presets.apply(), "Recommended roles applied");
  }

  /** Keeps the pharmacy's own roles; the notice is not shown again. */
  @PostMapping("/roles/recommendations/dismiss")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<RolePresets.Recommendations> dismissRecommendations() {
    return ApiResponse.ok(presets.dismiss(), "The pharmacy keeps its roles");
  }

  /** The permissions a tenant role may hold. */
  @GetMapping("/permissions")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<List<PermissionView>> permissions() {
    return ApiResponse.ok(Permissions.tenantCatalogue(), "Permissions retrieved");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RoleRequest(
      String code, String name, String description, List<String> permissions) {}
}
