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
import tz.co.pharmacy.delivery.api.CountsAgainst;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;
import tz.co.pharmacy.platform.kernel.Refusal;

@RestController
@RequestMapping("/api/v1/branches")
public class BranchHandler {

  private final BranchService branches;
  private final tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache;

  public BranchHandler(
      BranchService branches, tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache) {
    this.branches = branches;
    this.accessCache = accessCache;
  }

  /** Every branch of the tenant, for any member: the branch switcher needs them. */
  @GetMapping
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<List<BranchView>> list() {
    return ApiResponse.ok(branches.list(), "Branches retrieved");
  }

  @CountsAgainst(PlanLimit.BRANCHES)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = Permissions.BRANCHES_MANAGE)
  public ApiResponse<BranchView> create(@RequestBody BranchRequest body) {
    return ApiResponse.created(branches.create(command(body)), "Branch created");
  }

  @PutMapping("/{branchUid}")
  @Access(scope = AccessScope.TENANT, permission = Permissions.BRANCHES_MANAGE)
  public ApiResponse<BranchView> change(
      @PathVariable UUID branchUid, @RequestBody BranchRequest body) {
    BranchView changed =
        branches.change(branchUid, command(body), body.active() == null || body.active());
    // A closed branch is refused at the next request, on every instance (PERF-9).
    accessCache.forgetTenant(tz.co.pharmacy.platform.kernel.ExecutionContext.requireTenant());
    return ApiResponse.ok(changed, "Branch changed");
  }

  private static BranchCommand command(BranchRequest body) {
    if (body == null) {
      throw Refusal.malformed("A branch is required.");
    }
    return new BranchCommand(
        body.code(), body.name(), body.address(), body.phone(), body.licenceNumber());
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BranchRequest(
      String code,
      String name,
      String address,
      String phone,
      String licenceNumber,
      Boolean active) {}
}
