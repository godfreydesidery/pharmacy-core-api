package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpiryProvisions;
import tz.co.pharmacy.modules.finance.ports.inbound.VatApportionments;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Uids;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The month-end postings of the owner's decisions of 24 Nov 2026: the expiry write-down (STD-3) and
 * the partial-exemption VAT apportionment (STD-2). Each run is kept with the rule it used and
 * posted as one balanced journal entry (its source is the run), once per month: an advisory lock on
 * the pharmacy and month serialises two callers (the job and the route), and the unique key backs
 * it.
 */
@Service
class MonthEndBooksImpl implements ExpiryProvisions, VatApportionments {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcTemplate jdbc;
  private final JournalWriter writer;

  MonthEndBooksImpl(DataSource dataSource, JournalWriter writer) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.writer = writer;
  }

  // ------------------------------------------------------------------ expiry write-down

  private static final String PROVISION_COLUMNS =
      "uid, period, measured_on, enabled, rule, stock_at_cost, provision, previous_provision,"
          + " adjustment, journal_number, branches::text as branches, created_at";

  @Override
  @Transactional(readOnly = true)
  public Optional<ExpiryProvisions.Run> find(YearMonth period) {
    return jdbc
        .query(
            "select " + PROVISION_COLUMNS + " from finance.expiry_provisions where period = ?",
            this::provision,
            Date.valueOf(period.atDay(1)))
        .stream()
        .findFirst();
  }

  @Override
  @Transactional
  public ExpiryProvisions.Run record(
      YearMonth period,
      LocalDate measuredOn,
      boolean enabled,
      List<Band> bands,
      List<BranchMeasure> branches) {
    lock("expiry-provision:" + period);
    Optional<ExpiryProvisions.Run> done = find(period);
    if (done.isPresent()) {
      return done.get();
    }
    BigDecimal stock = Amounts.ZERO;
    BigDecimal provision = Amounts.ZERO;
    for (BranchMeasure branch : branches) {
      stock = stock.add(Amounts.money(branch.stockAtCost()));
      provision = provision.add(Amounts.money(branch.provision()));
    }
    if (!enabled) {
      provision = Amounts.ZERO;
    }
    BigDecimal previous =
        jdbc
            .query(
                "select provision from finance.expiry_provisions where period < ?"
                    + " order by period desc limit 1",
                (rs, i) -> rs.getBigDecimal(1),
                Date.valueOf(period.atDay(1)))
            .stream()
            .findFirst()
            .map(Amounts::money)
            .orElse(Amounts.ZERO);
    BigDecimal adjustment = provision.subtract(previous);
    UUID uid = Uids.newUid();
    String number = null;
    if (adjustment.signum() != 0) {
      writer.post(
          LedgerImpl.EXPIRY_PROVISION,
          uid,
          period.toString(),
          "Expiry write-down " + period,
          null,
          measuredOn,
          Postings.expiryProvision(adjustment));
      number = journalNumber(LedgerImpl.EXPIRY_PROVISION, uid);
    }
    jdbc.update(
        "insert into finance.expiry_provisions (uid, tenant_uid, period, measured_on, enabled,"
            + " rule, stock_at_cost, provision, previous_provision, adjustment, journal_number,"
            + " branches, created_at, created_by, created_by_uid)"
            + " values (?, platform.current_tenant_uid(), ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " cast(? as jsonb), ?, ?, ?)",
        uid,
        Date.valueOf(period.atDay(1)),
        Date.valueOf(measuredOn),
        enabled,
        rule(bands),
        stock,
        provision,
        previous,
        adjustment,
        number,
        JSON.writeValueAsString(branches),
        Timestamp.from(Clocks.now()),
        ExecutionContext.actor().reference(),
        ExecutionContext.actor().uid());
    return find(period).orElseThrow();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExpiryProvisions.Run> list(LocalDate from, LocalDate to) {
    return jdbc.query(
        "select "
            + PROVISION_COLUMNS
            + " from finance.expiry_provisions where period between ? and ?"
            + " order by period desc",
        this::provision,
        Date.valueOf(from.withDayOfMonth(1)),
        Date.valueOf(to));
  }

  private static String rule(List<Band> bands) {
    StringBuilder rule = new StringBuilder();
    for (Band band : bands) {
      if (!rule.isEmpty()) {
        rule.append(';');
      }
      rule.append(band.daysToExpiry())
          .append(':')
          .append(band.percent().stripTrailingZeros().toPlainString());
    }
    return rule.toString();
  }

  private ExpiryProvisions.Run provision(ResultSet rs, int i) throws SQLException {
    List<Band> bands =
        rs.getString("rule").isBlank()
            ? List.of()
            : java.util.Arrays.stream(rs.getString("rule").split(";"))
                .map(
                    pair ->
                        new Band(
                            Integer.parseInt(pair.split(":")[0]),
                            new BigDecimal(pair.split(":")[1]).setScale(2, RoundingMode.HALF_UP)))
                .toList();
    List<BranchMeasure> branches =
        JSON.readValue(rs.getString("branches"), new TypeReference<List<BranchMeasure>>() {});
    return new ExpiryProvisions.Run(
        rs.getObject("uid", UUID.class),
        YearMonth.from(rs.getDate("period").toLocalDate()).toString(),
        rs.getDate("measured_on").toLocalDate(),
        rs.getBoolean("enabled"),
        bands,
        Amounts.money(rs.getBigDecimal("stock_at_cost")),
        Amounts.money(rs.getBigDecimal("provision")),
        Amounts.money(rs.getBigDecimal("previous_provision")),
        Amounts.money(rs.getBigDecimal("adjustment")),
        rs.getString("journal_number"),
        branches,
        rs.getTimestamp("created_at").toInstant());
  }

  // ------------------------------------------------------------------ VAT apportionment

  private static final String APPORTIONMENT_COLUMNS =
      "uid, period, kind, method, ratio_percent, rounded_up, taxable_sales, exempt_sales,"
          + " direct_taxable_vat, direct_exempt_vat, residual_vat, non_reclaimable, adjustment,"
          + " journal_number, created_at";

  @Override
  @Transactional(readOnly = true)
  public Optional<VatApportionments.Run> find(YearMonth period, String kind) {
    return jdbc
        .query(
            "select "
                + APPORTIONMENT_COLUMNS
                + " from finance.vat_apportionments where period = ? and kind = ?",
            this::apportionment,
            Date.valueOf(period.atDay(1)),
            kind)
        .stream()
        .findFirst();
  }

  @Override
  @Transactional
  public VatApportionments.Run record(Measure measure, LocalDate bookOn) {
    YearMonth period = measure.period();
    lock("vat-apportionment:" + period + ":" + measure.kind());
    Optional<VatApportionments.Run> done = find(period, measure.kind());
    if (done.isPresent()) {
      return done.get();
    }
    BigDecimal residual = Amounts.money(measure.residualVat());
    BigDecimal directExempt = Amounts.money(measure.directExemptVat());
    BigDecimal reclaimableShare =
        measure.ratioPercent().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
    BigDecimal nonReclaimable =
        directExempt.add(
            Amounts.money(residual.multiply(BigDecimal.ONE.subtract(reclaimableShare))));
    BigDecimal adjustment = nonReclaimable;
    if (ANNUAL.equals(measure.kind())) {
      // The year's measure less what its months already moved (the true-up).
      BigDecimal posted =
          jdbc.queryForObject(
              "select coalesce(sum(adjustment), 0) from finance.vat_apportionments"
                  + " where kind = 'MONTHLY' and period between ? and ?",
              BigDecimal.class,
              Date.valueOf(period.minusMonths(11).atDay(1)),
              Date.valueOf(period.atDay(1)));
      adjustment = nonReclaimable.subtract(Amounts.money(posted));
    }
    UUID uid = Uids.newUid();
    String number = null;
    if (adjustment.signum() != 0) {
      writer.post(
          LedgerImpl.VAT_APPORTIONMENT,
          uid,
          period + " " + measure.kind(),
          (ANNUAL.equals(measure.kind()) ? "VAT apportionment true-up " : "VAT apportionment ")
              + period,
          null,
          bookOn,
          Postings.vatApportionment(adjustment));
      number = journalNumber(LedgerImpl.VAT_APPORTIONMENT, uid);
    }
    jdbc.update(
        "insert into finance.vat_apportionments (uid, tenant_uid, period, kind, method,"
            + " ratio_percent, rounded_up, taxable_sales, exempt_sales, direct_taxable_vat,"
            + " direct_exempt_vat, residual_vat, non_reclaimable, adjustment, journal_number,"
            + " created_at, created_by, created_by_uid)"
            + " values (?, platform.current_tenant_uid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?, ?)",
        uid,
        Date.valueOf(period.atDay(1)),
        measure.kind(),
        measure.method(),
        measure.ratioPercent(),
        measure.roundedUp(),
        Amounts.money(measure.taxableSales()),
        Amounts.money(measure.exemptSales()),
        Amounts.money(measure.directTaxableVat()),
        directExempt,
        residual,
        nonReclaimable,
        adjustment,
        number,
        Timestamp.from(Clocks.now()),
        ExecutionContext.actor().reference(),
        ExecutionContext.actor().uid());
    return find(period, measure.kind()).orElseThrow();
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal expenseInputVat(LocalDate from, LocalDate to) {
    return Amounts.money(
        jdbc.queryForObject(
            "select coalesce(sum(vat_amount), 0) from finance.expenses"
                + " where expense_date between ? and ?",
            BigDecimal.class,
            Date.valueOf(from),
            Date.valueOf(to)));
  }

  @Override
  @Transactional(readOnly = true)
  public List<VatApportionments.Run> runs(LocalDate from, LocalDate to) {
    return jdbc.query(
        "select "
            + APPORTIONMENT_COLUMNS
            + " from finance.vat_apportionments where period between ? and ?"
            + " order by period desc, kind desc",
        this::apportionment,
        Date.valueOf(from.withDayOfMonth(1)),
        Date.valueOf(to));
  }

  private VatApportionments.Run apportionment(ResultSet rs, int i) throws SQLException {
    return new VatApportionments.Run(
        rs.getObject("uid", UUID.class),
        YearMonth.from(rs.getDate("period").toLocalDate()).toString(),
        rs.getString("kind"),
        rs.getString("method"),
        rs.getBigDecimal("ratio_percent").setScale(2, RoundingMode.HALF_UP),
        rs.getBoolean("rounded_up"),
        Amounts.money(rs.getBigDecimal("taxable_sales")),
        Amounts.money(rs.getBigDecimal("exempt_sales")),
        Amounts.money(rs.getBigDecimal("direct_taxable_vat")),
        Amounts.money(rs.getBigDecimal("direct_exempt_vat")),
        Amounts.money(rs.getBigDecimal("residual_vat")),
        Amounts.money(rs.getBigDecimal("non_reclaimable")),
        Amounts.money(rs.getBigDecimal("adjustment")),
        rs.getString("journal_number"),
        rs.getTimestamp("created_at").toInstant());
  }

  // ------------------------------------------------------------------ shared

  private void lock(String what) {
    jdbc.queryForObject(
        "select 1 from (select pg_advisory_xact_lock(hashtext(?))) as locked",
        Integer.class,
        TenantContext.requireTenant() + ":" + what);
  }

  private String journalNumber(String sourceType, UUID sourceUid) {
    return jdbc
        .query(
            "select number from finance.journal_entries where source_type = ? and source_uid = ?",
            (rs, i) -> rs.getString(1),
            sourceType,
            sourceUid)
        .stream()
        .findFirst()
        .orElse(null);
  }
}
