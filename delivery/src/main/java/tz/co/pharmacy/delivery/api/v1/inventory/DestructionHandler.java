package tz.co.pharmacy.delivery.api.v1.inventory;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.inventory.ports.inbound.Destructions;
import tz.co.pharmacy.modules.inventory.ports.inbound.Destructions.DestructionCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.Destructions.DestructionView;
import tz.co.pharmacy.platform.kernel.Page;

/** The destruction register of the request's branch: expired or damaged stock destroyed. */
@RestController
@RequestMapping("/api/v1/destructions")
@RequiredArgsConstructor
public class DestructionHandler {

  private final Destructions destructions;

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<Page<DestructionView>> list(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(destructions.list(Pages.of(cursor, limit)), "Destructions retrieved");
  }

  @GetMapping("/{uid}")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<DestructionView> get(@PathVariable UUID uid) {
    return ApiResponse.ok(destructions.get(uid), "Destruction retrieved");
  }

  @Idempotent
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<DestructionView> record(@RequestBody DestructionCommand body) {
    return ApiResponse.created(destructions.record(body), "Destruction recorded");
  }
}
