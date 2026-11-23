package tz.co.pharmacy.delivery.api.v1.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.platform.crypto.KeyRotation;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Key rotation for a root administrator (runbook "Rotating keys"): which keys seal what, how many
 * stored values name each key, and the re-seal that moves them to each purpose's active key.
 */
@RestController
@RequestMapping("/api/v1/platform/keys")
public class KeyHandler {

  private final KeyRotation rotation;

  public KeyHandler(KeyRotation rotation) {
    this.rotation = rotation;
  }

  @GetMapping
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<KeyRotation.Status> status() {
    return ApiResponse.ok(rotation.status(), "Keys");
  }

  /** Starts the re-seal in the background; 409 {@code reseal_running} when it already runs. */
  @PostMapping("/reseal")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<KeyRotation.Status> reseal() {
    if (!rotation.start()) {
      throw Refusal.conflict(
          "reseal_running", "The re-seal is already running; follow it with GET /platform/keys.");
    }
    return ApiResponse.ok(rotation.status(), "Re-seal started");
  }
}
