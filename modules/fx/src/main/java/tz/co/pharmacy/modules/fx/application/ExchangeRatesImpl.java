package tz.co.pharmacy.modules.fx.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.fx.domain.ExchangeRate;
import tz.co.pharmacy.modules.fx.persistence.ExchangeRateRepository;
import tz.co.pharmacy.modules.fx.ports.inbound.Currencies;
import tz.co.pharmacy.modules.fx.ports.inbound.CurrencyView;
import tz.co.pharmacy.modules.fx.ports.inbound.ExchangeRates;
import tz.co.pharmacy.modules.fx.ports.inbound.LatestRateView;
import tz.co.pharmacy.modules.fx.ports.inbound.RateCommand;
import tz.co.pharmacy.modules.fx.ports.inbound.RateView;
import tz.co.pharmacy.modules.fx.ports.outbound.FxTenant;
import tz.co.pharmacy.modules.fx.ports.outbound.RateProvider;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
class ExchangeRatesImpl implements ExchangeRates {

  static final String MANUAL = "MANUAL";

  private final ExchangeRateRepository rates;
  private final Currencies currencies;
  private final FxTenant tenant;
  private final RateProvider provider;
  private final Clock clock;
  private final TenantCalendar calendar;

  ExchangeRatesImpl(
      ExchangeRateRepository rates,
      Currencies currencies,
      FxTenant tenant,
      RateProvider provider,
      Clock clock,
      TenantCalendar calendar) {
    this.calendar = calendar;
    this.rates = rates;
    this.currencies = currencies;
    this.tenant = tenant;
    this.provider = provider;
    this.clock = clock;
  }

  static final int STALE_AFTER_DAYS = 7;

  @Override
  @Transactional(readOnly = true)
  public BigDecimal rate(String base, String quote, LocalDate on) {
    return latest(base, quote, on).rate();
  }

  @Override
  @Transactional(readOnly = true)
  public LatestRateView latest(String base, String quote, LocalDate on) {
    String from = code(base, "base");
    String to = code(quote, "quote");
    LocalDate day = on == null ? calendar.today() : on;
    if (from.equals(to)) {
      return new LatestRateView(from, to, BigDecimal.ONE, day, "IDENTITY", false);
    }
    Optional<ExchangeRate> direct = find(from, to, day);
    Optional<ExchangeRate> opposite = find(to, from, day);
    // The more recent of the direct and the opposite rate wins; the direct one wins a tie.
    if (direct.isPresent()
        && (opposite.isEmpty()
            || !opposite.get().getEffectiveOn().isAfter(direct.get().getEffectiveOn()))) {
      ExchangeRate row = direct.get();
      return new LatestRateView(
          from, to, row.getRate(), row.getEffectiveOn(), row.getSource(), stale(row, day));
    }
    ExchangeRate row =
        opposite.orElseThrow(
            () ->
                Refusal.invalid(
                    "rate_missing",
                    "There is no exchange rate from "
                        + from
                        + " to "
                        + to
                        + " on "
                        + day
                        + ". Record one under Finance → Exchange rates.",
                    "currency"));
    return new LatestRateView(
        from,
        to,
        Conversion.invert(row.getRate()),
        row.getEffectiveOn(),
        "INVERSE",
        stale(row, day));
  }

  @Override
  public BigDecimal realisedDifference(
      BigDecimal foreignAmount, BigDecimal bookedRate, BigDecimal settlementRate, String base) {
    Checks.required(foreignAmount, "amount");
    Checks.required(bookedRate, "bookedRate");
    Checks.required(settlementRate, "settlementRate");
    return Conversion.difference(foreignAmount, bookedRate, settlementRate, code(base, "base"));
  }

  @Override
  public int minorUnits(String currency) {
    return Conversion.minorUnits(code(currency, "currency"));
  }

  private static boolean stale(ExchangeRate row, LocalDate day) {
    return row.getEffectiveOn().isBefore(day.minusDays(STALE_AFTER_DAYS));
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal convert(BigDecimal amount, String from, String to, LocalDate on) {
    Checks.required(amount, "amount");
    return Conversion.convert(amount, rate(from, to, on), code(to, "quote"));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<RateView> list(String quote, PageQuery query) {
    Specification<ExchangeRate> filter = Specs.all();
    if (quote != null && !quote.isBlank()) {
      String code = code(quote, "quote");
      Specification<ExchangeRate> asQuote = Specs.equal("quote", code);
      filter = filter.and(asQuote.or(Specs.equal("base", code)));
    }
    return Keyset.page(rates, filter, query, ExchangeRatesImpl::view);
  }

  @Override
  @Transactional
  public RateView record(RateCommand command) {
    Checks.required(command, "rate");
    String base = code(command.base(), "base");
    String quote = code(command.quote(), "quote");
    if (base.equals(quote)) {
      throw Refusal.invalid("same_currency", "base and quote must differ.", "quote");
    }
    BigDecimal rate = Checks.required(command.rate(), "rate");
    if (rate.signum() <= 0) {
      throw Refusal.invalid("invalid_rate", "rate must be more than zero.", "rate");
    }
    LocalDate today = calendar.today();
    LocalDate on = command.effectiveOn() == null ? today : command.effectiveOn();
    if (on.isAfter(today.plusDays(1))) {
      throw Refusal.invalid(
          "invalid_effective_on", "effectiveOn cannot be in the future.", "effectiveOn");
    }
    return view(save(base, quote, Conversion.normalise(rate), on, MANUAL));
  }

  @Override
  @Transactional
  public List<RateView> refresh() {
    String base = tenant.baseCurrency();
    Set<String> quotes = new TreeSet<>(tenant.acceptedCurrencies());
    quotes.remove(base);
    if (quotes.isEmpty()) {
      return List.of();
    }
    Map<String, BigDecimal> latest = provider.latest(base, quotes);
    LocalDate today = calendar.today();
    List<RateView> saved = new ArrayList<>();
    for (String quote : quotes) {
      BigDecimal perBase = latest.get(quote);
      if (perBase == null || perBase.signum() <= 0) {
        continue;
      }
      // Stored as foreign → base: base units per 1 foreign unit, the way documents quote it.
      saved.add(view(save(quote, base, Conversion.invert(perBase), today, provider.name())));
    }
    return saved;
  }

  private Optional<ExchangeRate> find(String base, String quote, LocalDate on) {
    return rates.findFirstByBaseAndQuoteAndEffectiveOnLessThanEqualOrderByEffectiveOnDescIdDesc(
        base, quote, on);
  }

  private ExchangeRate save(
      String base, String quote, BigDecimal rate, LocalDate on, String source) {
    return rates
        .findByBaseAndQuoteAndEffectiveOnAndSource(base, quote, on, source)
        .map(
            existing -> {
              existing.replaceRate(rate);
              return existing;
            })
        .orElseGet(() -> rates.save(new ExchangeRate(base, quote, rate, on, source)));
  }

  private String code(String value, String field) {
    String text = Checks.text(value, field, 3);
    return currencies
        .find(text)
        .map(CurrencyView::code)
        .orElseThrow(
            () ->
                Refusal.invalid(
                    "invalid_currency", field + " is not an ISO 4217 currency code.", field));
  }

  static RateView view(ExchangeRate row) {
    return new RateView(
        row.getUid(),
        row.getBase(),
        row.getQuote(),
        row.getRate().stripTrailingZeros(),
        row.getEffectiveOn(),
        row.getSource(),
        row.getCreatedAt());
  }
}
