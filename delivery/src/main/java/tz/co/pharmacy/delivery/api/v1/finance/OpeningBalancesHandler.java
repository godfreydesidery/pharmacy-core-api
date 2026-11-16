package tz.co.pharmacy.delivery.api.v1.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.application.onboarding.ports.inbound.OpeningBalances;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.finance.ports.inbound.OpeningBalanceView;
import tz.co.pharmacy.platform.kernel.Refusal;

/** A pharmacy's opening balances: posted once, then shown as they were posted. */
@RestController
@RequestMapping("/api/v1/opening-balances")
public class OpeningBalancesHandler {

  private final OpeningBalances openingBalances;

  public OpeningBalancesHandler(OpeningBalances openingBalances) {
    this.openingBalances = openingBalances;
  }

  /** {@code posted} null until done; {@code stockAtCost} is what inventory would default to. */
  @GetMapping
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = Permissions.FINANCE_VIEW)
  public ApiResponse<StatusResponse> status() {
    return ApiResponse.ok(
        new StatusResponse(openingBalances.current().orElse(null), openingBalances.stockAtCost()),
        "Opening balances retrieved");
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = Permissions.FINANCE_MANAGE)
  public ApiResponse<OpeningBalanceView> post(@RequestBody OpeningRequest body) {
    if (body == null) {
      throw Refusal.malformed("Opening balances are required.");
    }
    return ApiResponse.created(
        openingBalances.post(
            new OpeningBalances.Command(
                body.asOf(),
                body.cash(),
                body.bank(),
                body.mobileMoney(),
                body.inventory(),
                amounts(body.receivables()),
                amounts(body.payables()))),
        "Opening balances posted");
  }

  private static List<OpeningBalances.Amount> amounts(List<PartyAmount> lines) {
    return lines == null
        ? List.of()
        : lines.stream()
            .map(line -> new OpeningBalances.Amount(line.partyUid(), line.amount()))
            .toList();
  }

  public record StatusResponse(OpeningBalanceView posted, BigDecimal stockAtCost) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OpeningRequest(
      LocalDate asOf,
      BigDecimal cash,
      BigDecimal bank,
      BigDecimal mobileMoney,
      BigDecimal inventory,
      List<PartyAmount> receivables,
      List<PartyAmount> payables) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PartyAmount(UUID partyUid, BigDecimal amount) {}
}
