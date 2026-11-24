package tz.co.pharmacy.delivery.api.v1.sales;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.sales.ports.inbound.TillEvents;
import tz.co.pharmacy.modules.sales.ports.inbound.TillEvents.TillEventSummary;
import tz.co.pharmacy.modules.sales.ports.inbound.TillEvents.TillEventView;

/**
 * The sales that did not happen (FRD-20): the till reports a cart cancelled or cleared with lines
 * on it, a parked sale thrown away and a no-sale drawer opening; managers read them per cashier and
 * day.
 */
@RestController
public class TillEventHandler {

  private final TillEvents events;

  public TillEventHandler(TillEvents events) {
    this.events = events;
  }

  @PostMapping("/api/v1/till-events")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<TillEventView> record(@RequestBody TillEvents.RecordTillEvent body) {
    return ApiResponse.created(events.record(body), "Till event recorded");
  }

  @GetMapping("/api/v1/reports/till-events")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "reports.view")
  public ApiResponse<List<TillEventSummary>> summary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(events.summary(from, to), "Till events");
  }
}
