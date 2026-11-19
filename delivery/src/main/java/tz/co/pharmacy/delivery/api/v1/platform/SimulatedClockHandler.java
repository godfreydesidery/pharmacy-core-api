package tz.co.pharmacy.delivery.api.v1.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.SimulatedClock;
import tz.co.pharmacy.platform.persistence.audit.AuditConfiguration.AuditPartitions;

/**
 * The simulated world's clock (the {@code sim} profile only; the route does not exist otherwise). A
 * root administrator reads it and moves it forward, by a duration or to a moment.
 */
@Profile("sim")
@RestController
@RequestMapping("/api/v1/platform/sim/clock")
public class SimulatedClockHandler {

  private final SimulatedClock clock;
  private final AuditPartitions auditPartitions;

  public SimulatedClockHandler(SimulatedClock clock, AuditPartitions auditPartitions) {
    this.clock = clock;
    this.auditPartitions = auditPartitions;
  }

  @GetMapping
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<ClockView> read() {
    return ApiResponse.ok(new ClockView(clock.instant()), "Simulated time");
  }

  /** {@code advance}: an ISO-8601 duration such as {@code P1D} or {@code PT8H}; or {@code to}. */
  @PostMapping
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<ClockView> move(@RequestBody MoveRequest body) {
    if (body == null || (body.advance() == null) == (body.to() == null)) {
      throw Refusal.invalid(
          "clock_move", "Give either advance (an ISO duration) or to (an ISO instant).", "advance");
    }
    Instant now;
    try {
      now =
          body.advance() != null
              ? clock.advance(Duration.parse(body.advance()))
              : clock.moveTo(Instant.parse(body.to()));
    } catch (DateTimeParseException failure) {
      throw Refusal.invalid(
          "clock_move",
          "Not an ISO duration (P1D) or instant (2027-01-01T06:00:00Z): "
              + failure.getParsedString(),
          body.advance() != null ? "advance" : "to");
    }
    auditPartitions.ensure();
    return ApiResponse.ok(new ClockView(now), "Simulated clock moved");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MoveRequest(String advance, String to) {}

  public record ClockView(Instant now) {}
}
