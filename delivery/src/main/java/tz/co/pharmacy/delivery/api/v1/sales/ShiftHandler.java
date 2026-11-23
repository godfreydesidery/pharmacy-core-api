package tz.co.pharmacy.delivery.api.v1.sales;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.sales.ports.inbound.Shifts;
import tz.co.pharmacy.modules.sales.ports.inbound.Shifts.ShiftView;
import tz.co.pharmacy.platform.kernel.Page;

/** Till shifts: open with a float, see the figures so far (X), close with the cash counted (Z). */
@RestController
@RequestMapping("/api/v1/shifts")
@RequiredArgsConstructor
public class ShiftHandler {

  private final Shifts shifts;

  @GetMapping("/current")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<ShiftView> current() {
    return ApiResponse.ok(shifts.current().orElse(null), "Current shift retrieved");
  }

  /** The cash counted at the branch's last close, to suggest the float; null before any. */
  @GetMapping("/last-count")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<Shifts.LastCount> lastCount() {
    return ApiResponse.ok(shifts.lastCount().orElse(null), "Last count retrieved");
  }

  public record OpenRequest(BigDecimal openingFloat) {}

  @PostMapping("/open")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<ShiftView> open(@RequestBody(required = false) OpenRequest body) {
    return ApiResponse.created(
        shifts.open(body == null ? null : body.openingFloat()), "Shift opened");
  }

  public record CloseRequest(BigDecimal countedCash, String note) {}

  @PostMapping("/close")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<ShiftView> close(@RequestBody CloseRequest body) {
    return ApiResponse.ok(
        shifts.close(body == null ? null : body.countedCash(), body == null ? null : body.note()),
        "Shift closed");
  }

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "reports.view")
  public ApiResponse<Page<ShiftView>> list(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(shifts.list(Pages.of(cursor, limit)), "Shifts retrieved");
  }
}
