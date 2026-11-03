package tz.co.pharmacy.delivery.api.v1.inventory;

import java.util.List;
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
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferLineCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferView;
import tz.co.pharmacy.platform.kernel.Page;

/** Stock transfers between branches, sent from and received at the request's branch. */
@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferHandler {

  private final StockQueries stockQueries;
  private final StockService stockService;

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<Page<TransferView>> list(
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        stockQueries.transfers(direction, Pages.of(cursor, limit)), "Transfers retrieved");
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "inventory.transfer")
  public ApiResponse<TransferView> create(@RequestBody TransferRequest body) {
    List<TransferLineCommand> lines =
        body.lines() == null
            ? List.of()
            : body.lines().stream()
                .map(
                    line ->
                        new TransferLineCommand(
                            line.productUid(),
                            line.quantity() == null ? 0 : line.quantity(),
                            line.serialNumbers()))
                .toList();
    return ApiResponse.created(
        stockService.dispatch(new TransferCommand(body.toBranchUid(), body.note(), lines)),
        "Transfer dispatched");
  }

  @PostMapping("/{transferUid}/receive")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.transfer")
  public ApiResponse<TransferView> receive(@PathVariable UUID transferUid) {
    return ApiResponse.ok(stockService.receiveTransfer(transferUid), "Transfer received");
  }

  public record TransferRequest(UUID toBranchUid, String note, List<TransferLineRequest> lines) {}

  public record TransferLineRequest(
      UUID productUid, Integer quantity, List<String> serialNumbers) {}
}
