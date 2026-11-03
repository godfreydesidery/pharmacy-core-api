package tz.co.pharmacy.delivery.api.v1.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
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
import tz.co.pharmacy.modules.fx.ports.inbound.Currencies;
import tz.co.pharmacy.modules.fx.ports.inbound.CurrencyView;
import tz.co.pharmacy.modules.fx.ports.inbound.ExchangeRates;
import tz.co.pharmacy.modules.fx.ports.inbound.LatestRateView;
import tz.co.pharmacy.modules.fx.ports.inbound.RateCommand;
import tz.co.pharmacy.modules.fx.ports.inbound.RateView;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** The currency catalogue and the tenant's exchange rates. */
@RestController
@RequestMapping("/api/v1")
public class FxHandler {

  private final Currencies currencies;
  private final ExchangeRates rates;

  public FxHandler(Currencies currencies, ExchangeRates rates) {
    this.currencies = currencies;
    this.rates = rates;
  }

  /** The offered currencies with their ISO 4217 minor units, for pickers and formatting. */
  @GetMapping("/currencies")
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<List<CurrencyView>> currencies() {
    return ApiResponse.ok(currencies.all(), "Currencies retrieved");
  }

  /** Newest first; {@code quote} filters by either side of the pair. */
  @GetMapping("/fx/rates")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<Page<RateView>> list(
      @RequestParam(required = false) String quote,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(rates.list(quote, Pages.of(cursor, limit)), "Exchange rates retrieved");
  }

  /**
   * The rate in force for a pair, such as {@code ?base=USD&quote=TZS} (TZS per 1 USD), with {@code
   * stale: true} when it is older than 7 days; 422 rate_missing when there is none. Any member may
   * read it: the POS and purchasing screens prefill rates with it.
   */
  @GetMapping("/fx/rates/latest")
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<LatestRateView> latest(
      @RequestParam String base,
      @RequestParam String quote,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
    return ApiResponse.ok(rates.latest(base, quote, on), "Exchange rate retrieved");
  }

  @PostMapping("/fx/rates")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "finance.manage")
  public ApiResponse<RateView> record(@RequestBody RateRequest body) {
    if (body == null) {
      throw Refusal.malformed("A rate is required.");
    }
    return ApiResponse.created(
        rates.record(new RateCommand(body.base(), body.quote(), body.rate(), body.effectiveOn())),
        "Exchange rate recorded");
  }

  /** Pulls today's rates of the accepted currencies from the configured provider. */
  @PostMapping("/fx/rates/refresh")
  @Access(scope = AccessScope.TENANT, permission = "finance.manage")
  public ApiResponse<List<RateView>> refresh() {
    return ApiResponse.ok(rates.refresh(), "Exchange rates refreshed");
  }

  /** Units of {@code quote} per 1 {@code base}, such as base USD, quote TZS, rate 2650. */
  public record RateRequest(String base, String quote, BigDecimal rate, LocalDate effectiveOn) {}
}
