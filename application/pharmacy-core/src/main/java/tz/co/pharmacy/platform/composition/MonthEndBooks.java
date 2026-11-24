package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpiryProvisions;
import tz.co.pharmacy.modules.finance.ports.inbound.MonthEndRuns;
import tz.co.pharmacy.modules.finance.ports.inbound.VatApportionments;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierDebitNotes;
import tz.co.pharmacy.modules.sales.ports.inbound.SalesReports;
import tz.co.pharmacy.modules.tenancy.ports.inbound.ProvisionBand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.VatApportionmentMethod;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.TaxCategory;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * The month-end measures of the owner's decisions of 24 Nov 2026, in the current pharmacy, where
 * stock, settings, sales and purchases meet; finance records and posts them.
 *
 * <p><b>Expiry write-down (STD-3).</b> Stock on hand at FEFO batch cost, by expiry, measured at the
 * month's last day: a batch whose expiry is at most a band's days after it (0: already expired)
 * takes the first such band's percentage. Stock without an expiry date, or further from expiry than
 * the last band, has none. Quantities are as they are when the run measures (early on the 1st), as
 * close to the month end as the books allow.
 *
 * <p><b>VAT apportionment (STD-2 partial exemption).</b> Input VAT of a month's goods receipts and
 * supplier debit notes is split by its receipt's lines: the lines of EXEMPT products carry
 * non-reclaimable VAT (direct exempt), the lines of STANDARD and ZERO_RATED products reclaimable
 * VAT (direct taxable), each by its share of the receipt's value; a line whose product is unknown
 * is residual. Every expense's input VAT is residual (overheads serve both kinds of sale). The
 * residual is reclaimable at the ratio: AUTOMATIC, the taxable share of the month's sales net of
 * VAT (STANDARD and ZERO_RATED over all, returns off, debit notes on; 100% in a month without
 * sales), rounded up to the next whole percent when the setting says so; FIXED, the agreed
 * percentage. In the year-end month an ANNUAL run measures the whole financial year the same way
 * and posts the difference from the months (the true-up). The method is the accountant's to
 * confirm.
 */
@Component
class MonthEndBooks implements MonthEndRuns {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final SettingsService settings;
  private final StockQueries stock;
  private final ExpiryProvisions provisions;
  private final VatApportionments apportionments;
  private final SupplierDebitNotes purchases;
  private final CatalogueService catalogue;
  private final SalesReports sales;
  private final TenantCalendar calendar;

  MonthEndBooks(
      SettingsService settings,
      StockQueries stock,
      ExpiryProvisions provisions,
      VatApportionments apportionments,
      SupplierDebitNotes purchases,
      CatalogueService catalogue,
      SalesReports sales,
      TenantCalendar calendar) {
    this.settings = settings;
    this.stock = stock;
    this.provisions = provisions;
    this.apportionments = apportionments;
    this.purchases = purchases;
    this.catalogue = catalogue;
    this.sales = sales;
    this.calendar = calendar;
  }

  @Override
  public YearMonth lastCompleteMonth() {
    return YearMonth.from(calendar.today()).minusMonths(1);
  }

  // ------------------------------------------------------------------ expiry write-down

  @Override
  public ExpiryProvisions.ExpiryProvisionRun expiryProvision(YearMonth period) {
    var done = provisions.find(period);
    if (done.isPresent()) {
      return done.get();
    }
    SettingsView rule = settings.current();
    LocalDate measuredOn = period.atEndOfMonth();
    List<ProvisionBand> bands =
        rule.expiryProvisionBands().stream()
            .sorted(Comparator.comparingInt(ProvisionBand::daysToExpiry))
            .toList();
    return provisions.record(
        period,
        measuredOn,
        rule.expiryProvisionEnabled(),
        bands.stream()
            .map(b -> new ExpiryProvisions.ProvisionRuleBand(b.daysToExpiry(), b.percent()))
            .toList(),
        measure(stock.stockByExpiry(), bands, measuredOn));
  }

  /** Each branch's stock at cost and, per band, its cost at risk and provision. */
  static List<ExpiryProvisions.ProvisionBranchMeasure> measure(
      List<StockQueries.ExpiryExposure> exposures,
      List<ProvisionBand> bands,
      LocalDate measuredOn) {
    Map<UUID, BigDecimal> stockAt = new LinkedHashMap<>();
    Map<UUID, BigDecimal[]> atRisk = new LinkedHashMap<>();
    for (StockQueries.ExpiryExposure exposure : exposures) {
      stockAt.merge(exposure.branchUid(), Amounts.money(exposure.cost()), BigDecimal::add);
      BigDecimal[] byBand =
          atRisk.computeIfAbsent(
              exposure.branchUid(),
              uid -> {
                BigDecimal[] zero = new BigDecimal[bands.size()];
                java.util.Arrays.fill(zero, Amounts.ZERO);
                return zero;
              });
      if (exposure.expiryDate() == null) {
        continue;
      }
      long daysLeft = ChronoUnit.DAYS.between(measuredOn, exposure.expiryDate());
      for (int i = 0; i < bands.size(); i++) {
        if (daysLeft <= bands.get(i).daysToExpiry()) {
          byBand[i] = byBand[i].add(Amounts.money(exposure.cost()));
          break;
        }
      }
    }
    List<ExpiryProvisions.ProvisionBranchMeasure> branches = new ArrayList<>();
    stockAt.forEach(
        (branch, cost) -> {
          BigDecimal[] byBand = atRisk.get(branch);
          List<ExpiryProvisions.ProvisionBandMeasure> measured = new ArrayList<>();
          BigDecimal total = Amounts.ZERO;
          for (int i = 0; i < bands.size(); i++) {
            BigDecimal provision =
                Amounts.money(byBand[i].multiply(bands.get(i).percent()).divide(HUNDRED));
            total = total.add(provision);
            measured.add(
                new ExpiryProvisions.ProvisionBandMeasure(
                    bands.get(i).daysToExpiry(), bands.get(i).percent(), byBand[i], provision));
          }
          branches.add(new ExpiryProvisions.ProvisionBranchMeasure(branch, cost, total, measured));
        });
    return branches;
  }

  // ------------------------------------------------------------------ VAT apportionment

  @Override
  public List<VatApportionments.VatApportionmentRun> vatApportionment(YearMonth period) {
    SettingsView rule = settings.current();
    List<VatApportionments.VatApportionmentRun> runs = new ArrayList<>();
    if (!rule.vatRegistered()) {
      return runs;
    }
    runs.add(
        apportionments
            .find(period, VatApportionments.MONTHLY)
            .orElseGet(
                () ->
                    apportionments.record(
                        measure(rule, period, period, VatApportionments.MONTHLY),
                        period.atEndOfMonth())));
    if (period.getMonthValue() == rule.vatYearEndMonth()
        && rule.vatApportionmentMethod() == VatApportionmentMethod.AUTOMATIC) {
      runs.add(
          apportionments
              .find(period, VatApportionments.ANNUAL)
              .orElseGet(
                  () ->
                      apportionments.record(
                          measure(rule, period.minusMonths(11), period, VatApportionments.ANNUAL),
                          period.atEndOfMonth())));
    }
    return runs;
  }

  /** The months first..last measured as one (a month, or the financial year). */
  private VatApportionments.VatApportionmentMeasure measure(
      SettingsView rule, YearMonth first, YearMonth last, String kind) {
    ZoneId zone = calendar.zone();
    LocalDate from = first.atDay(1);
    LocalDate to = last.atEndOfMonth();
    BigDecimal taxable = Amounts.ZERO;
    BigDecimal exempt = Amounts.ZERO;
    for (SalesReports.VatCategoryLine line : sales.vatByCategory(from, to, zone)) {
      if (line.category() == TaxCategory.EXEMPT) {
        exempt = exempt.add(line.net());
      } else {
        taxable = taxable.add(line.net());
      }
    }
    BigDecimal[] split = splitPurchases(from, to, zone);
    BigDecimal residual = split[2].add(apportionments.expenseInputVat(from, to));
    boolean automatic = rule.vatApportionmentMethod() == VatApportionmentMethod.AUTOMATIC;
    boolean roundUp = automatic && rule.vatRatioRoundUp();
    BigDecimal ratio =
        automatic
            ? ratio(taxable, exempt, roundUp)
            : rule.vatFixedRecoverablePercent().setScale(4, RoundingMode.HALF_UP);
    return new VatApportionments.VatApportionmentMeasure(
        last,
        kind,
        rule.vatApportionmentMethod().name(),
        ratio,
        roundUp,
        taxable,
        exempt,
        split[0],
        split[1],
        residual);
  }

  /**
   * The reclaimable share of residual input VAT, in percent: taxable over all sales (100 without
   * sales), up to the next whole percent when {@code roundUp}, else to 2 decimals.
   */
  static BigDecimal ratio(BigDecimal taxable, BigDecimal exempt, boolean roundUp) {
    BigDecimal all = taxable.add(exempt);
    if (all.signum() <= 0 || exempt.signum() <= 0) {
      return HUNDRED.setScale(4);
    }
    if (taxable.signum() <= 0) {
      return BigDecimal.ZERO.setScale(4);
    }
    BigDecimal share = taxable.multiply(HUNDRED).divide(all, 10, RoundingMode.HALF_UP);
    return (roundUp
            ? share.setScale(0, RoundingMode.CEILING)
            : share.setScale(2, RoundingMode.HALF_UP))
        .setScale(4);
  }

  /** Input VAT of receipts and supplier debit notes: {direct taxable, direct exempt, residual}. */
  private BigDecimal[] splitPurchases(LocalDate from, LocalDate to, ZoneId zone) {
    Instant start = from.atStartOfDay(zone).toInstant();
    Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
    List<SupplierDebitNotes.ReceiptInputVat> documents = purchases.inputVat(start, end);
    Set<UUID> products = new HashSet<>();
    documents.forEach(d -> d.lines().forEach(l -> products.add(l.productUid())));
    Map<UUID, TaxCategory> categories = new HashMap<>();
    if (!products.isEmpty()) {
      for (ProductView product : catalogue.products(products)) {
        categories.put(product.uid(), product.taxCategory());
      }
    }
    BigDecimal taxable = Amounts.ZERO;
    BigDecimal exempt = Amounts.ZERO;
    BigDecimal residual = Amounts.ZERO;
    for (SupplierDebitNotes.ReceiptInputVat document : documents) {
      BigDecimal[] parts = split(document, categories);
      taxable = taxable.add(parts[0]);
      exempt = exempt.add(parts[1]);
      residual = residual.add(parts[2]);
    }
    return new BigDecimal[] {taxable, exempt, residual};
  }

  /**
   * One document's input VAT by its lines' categories; the residual takes what rounding leaves, so
   * the three parts add up to the VAT exactly.
   */
  static BigDecimal[] split(
      SupplierDebitNotes.ReceiptInputVat document, Map<UUID, TaxCategory> categories) {
    BigDecimal vat = Amounts.money(document.vat());
    BigDecimal taxableValue = Amounts.ZERO;
    BigDecimal exemptValue = Amounts.ZERO;
    BigDecimal all = Amounts.ZERO;
    for (SupplierDebitNotes.ReceiptLineValue line : document.lines()) {
      all = all.add(line.value());
      TaxCategory category = categories.get(line.productUid());
      if (category == TaxCategory.EXEMPT) {
        exemptValue = exemptValue.add(line.value());
      } else if (category != null) {
        taxableValue = taxableValue.add(line.value());
      }
    }
    if (all.signum() <= 0) {
      return new BigDecimal[] {Amounts.ZERO, Amounts.ZERO, vat};
    }
    BigDecimal taxable =
        Amounts.money(vat.multiply(taxableValue).divide(all, 10, RoundingMode.HALF_UP));
    BigDecimal exempt =
        Amounts.money(vat.multiply(exemptValue).divide(all, 10, RoundingMode.HALF_UP));
    if (exemptValue.signum() == 0 && taxableValue.compareTo(all) == 0) {
      taxable = vat;
    } else if (taxableValue.signum() == 0 && exemptValue.compareTo(all) == 0) {
      exempt = vat;
    }
    BigDecimal residual = vat.subtract(taxable).subtract(exempt);
    if (residual.signum() < 0) {
      exempt = exempt.add(residual);
      residual = Amounts.ZERO;
    }
    return new BigDecimal[] {taxable, exempt, residual};
  }
}
