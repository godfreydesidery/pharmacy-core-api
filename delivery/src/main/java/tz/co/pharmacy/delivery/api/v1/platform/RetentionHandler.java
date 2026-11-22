package tz.co.pharmacy.delivery.api.v1.platform;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.platform.persistence.retention.Retention;

/**
 * Runs the nightly retention now (what it deletes and why: {@link Retention}). For a root
 * administrator after changing the periods, and for tests and the simulated world.
 */
@RestController
@RequestMapping("/api/v1/platform/retention")
public class RetentionHandler {

  private final Retention retention;

  public RetentionHandler(Retention retention) {
    this.retention = retention;
  }

  @PostMapping("/run")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<Map<String, Long>> run() {
    return ApiResponse.ok(retention.run(), "Retention run");
  }
}
