package tz.co.pharmacy.delivery.api.v1.inventory;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockCounts;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockCounts.CountEntry;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockCounts.StockCountView;
import tz.co.pharmacy.platform.kernel.Page;

/** Stock-takes: start a count sheet, record what was counted, post the differences. */
@RestController
@RequestMapping("/api/v1/stock-counts")
@RequiredArgsConstructor
public class StockCountHandler {

  private final StockCounts counts;

  public record StartRequest(String note) {}

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<StockCountView> start(@RequestBody(required = false) StartRequest body) {
    return ApiResponse.created(
        counts.start(body == null ? null : body.note()), "Stock-take started");
  }

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<Page<StockCountView>> list(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(counts.list(Pages.of(cursor, limit)), "Stock-takes retrieved");
  }

  @GetMapping("/{uid}")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<StockCountView> get(@PathVariable UUID uid) {
    return ApiResponse.ok(counts.get(uid), "Stock-take retrieved");
  }

  @PutMapping("/{uid}/counts")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<StockCountView> record(
      @PathVariable UUID uid, @RequestBody List<CountEntry> body) {
    return ApiResponse.ok(counts.record(uid, body), "Counts recorded");
  }

  @PostMapping("/{uid}/post")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<StockCountView> post(@PathVariable UUID uid) {
    return ApiResponse.ok(counts.post(uid), "Stock-take posted");
  }
}
