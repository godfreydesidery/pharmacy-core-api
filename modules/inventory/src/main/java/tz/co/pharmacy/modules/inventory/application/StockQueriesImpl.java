package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.inventory.domain.Movement;
import tz.co.pharmacy.modules.inventory.domain.Transfer;
import tz.co.pharmacy.modules.inventory.persistence.MovementRepository;
import tz.co.pharmacy.modules.inventory.persistence.SerialUnitRepository;
import tz.co.pharmacy.modules.inventory.persistence.StockLevelRepository;
import tz.co.pharmacy.modules.inventory.persistence.TransferRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.BatchView;
import tz.co.pharmacy.modules.inventory.ports.inbound.ExpiringBatchView;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementView;
import tz.co.pharmacy.modules.inventory.ports.inbound.SerialStatus;
import tz.co.pharmacy.modules.inventory.ports.inbound.SerialUnitView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockFilter;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockItemView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockStatus;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockSummaryView;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferView;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

/**
 * Reads of stock. The aggregates are plain SQL over the module's own tables; row-level security
 * keeps them to the current tenant without a tenant predicate.
 */
@Service
@Transactional(readOnly = true)
class StockQueriesImpl implements StockQueries {

  private final JdbcTemplate jdbc;
  private final StockLevelRepository levels;
  private final MovementRepository movements;
  private final TransferRepository transfers;
  private final SerialUnitRepository units;
  private final InventoryViews views;
  private final Clock clock;
  private final TenantCalendar calendar;

  StockQueriesImpl(
      DataSource dataSource,
      StockLevelRepository levels,
      MovementRepository movements,
      TransferRepository transfers,
      SerialUnitRepository units,
      InventoryViews views,
      Clock clock,
      TenantCalendar calendar) {
    this.calendar = calendar;
    this.jdbc = new JdbcTemplate(dataSource);
    this.levels = levels;
    this.movements = movements;
    this.transfers = transfers;
    this.units = units;
    this.views = views;
    this.clock = clock;
  }

  @Override
  public Page<StockItemView> stock(StockFilter filter, PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    List<Object> parameters = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "select * from (select p.id, p.product_uid, p.name, p.generic_name, p.sku,"
                + " p.dosage_form, p.strength, p.category_uid, p.category_name, p.reorder_level,"
                + " p.selling_price, p.product_type, p.unit_of_measure, p.tracking_mode,"
                + " coalesce(s.quantity, 0) as quantity, s.nearest_expiry,"
                + " coalesce(s.batch_count, 0) as batch_count,"
                + " coalesce(s.cost_value, 0) as cost_value"
                + " from inventory.products p left join ("
                + "   select sl.product_uid, sum(sl.quantity) as quantity,"
                + "     min(b.expiry_date) filter (where sl.quantity > 0) as nearest_expiry,"
                + "     count(*) filter (where sl.quantity > 0) as batch_count,"
                + "     sum(sl.quantity * b.unit_cost) as cost_value"
                + "   from inventory.stock_levels sl"
                + "   join inventory.batches b on b.id = sl.batch_id"
                + "   where sl.branch_uid = ? group by sl.product_uid"
                + " ) s on s.product_uid = p.product_uid where p.active) x where true");
    parameters.add(branch);
    if (filter != null && filter.q() != null && !filter.q().isBlank()) {
      String pattern = "%" + filter.q().trim().toLowerCase() + "%";
      sql.append(
          " and (lower(x.name) like ? or lower(coalesce(x.generic_name, '')) like ?"
              + " or lower(coalesce(x.sku, '')) like ?)");
      parameters.add(pattern);
      parameters.add(pattern);
      parameters.add(pattern);
    }
    if (filter != null && filter.categoryUid() != null) {
      sql.append(" and x.category_uid = ?");
      parameters.add(filter.categoryUid());
    }
    if (filter != null && filter.productType() != null && !filter.productType().isBlank()) {
      sql.append(" and x.product_type = ?");
      parameters.add(filter.productType().trim().toUpperCase());
    }
    if (filter != null && filter.status() != null) {
      sql.append(
          switch (filter.status()) {
            case OUT_OF_STOCK -> " and x.quantity <= 0";
            case LOW_STOCK -> " and x.quantity > 0 and x.quantity <= x.reorder_level";
            case IN_STOCK -> " and x.quantity > x.reorder_level";
          });
    }
    sql.append(" order by lower(x.name), x.id limit ? offset ?");
    parameters.add(query.limit() + 1);
    parameters.add(query.offset());
    List<StockItemView> rows =
        jdbc.query(
            sql.toString(),
            (rs, row) -> {
              int quantity = rs.getInt("quantity");
              int reorderLevel = rs.getInt("reorder_level");
              return new StockItemView(
                  rs.getObject("product_uid", UUID.class),
                  rs.getString("name"),
                  rs.getString("generic_name"),
                  rs.getString("sku"),
                  rs.getString("dosage_form"),
                  rs.getString("strength"),
                  rs.getString("category_name"),
                  quantity,
                  reorderLevel,
                  StockStatus.of(quantity, reorderLevel),
                  date(rs, "nearest_expiry"),
                  rs.getInt("batch_count"),
                  Amounts.money(rs.getBigDecimal("selling_price")),
                  Amounts.money(rs.getBigDecimal("cost_value")),
                  rs.getString("product_type"),
                  rs.getString("unit_of_measure"),
                  rs.getString("tracking_mode"));
            },
            parameters.toArray());
    return Page.offset(rows, query);
  }

  @Override
  public StockSummaryView summary(Set<UUID> branchUids, int expiryAlertDays) {
    LocalDate today = today();
    List<Object> parameters = new ArrayList<>();
    String branchCondition = branchCondition("sl.branch_uid", branchUids, parameters);
    String products =
        "select count(*) as products,"
            + " count(*) filter (where q > reorder_level) as in_stock,"
            + " count(*) filter (where q > 0 and q <= reorder_level) as low_stock,"
            + " count(*) filter (where q <= 0) as out_of_stock,"
            + " coalesce(sum(cost_value), 0) as cost_value,"
            + " coalesce(sum(q * selling_price), 0) as retail_value"
            + " from (select p.product_uid, p.reorder_level, p.selling_price,"
            + "   coalesce(sum(sl.quantity), 0) as q,"
            + "   coalesce(sum(sl.quantity * b.unit_cost), 0) as cost_value"
            + "   from inventory.products p"
            + "   left join inventory.stock_levels sl on sl.product_uid = p.product_uid and "
            + branchCondition
            + "   left join inventory.batches b on b.id = sl.batch_id"
            + "   where p.active group by p.product_uid, p.reorder_level, p.selling_price) x";
    Map<String, Object> figures = jdbc.queryForMap(products, parameters.toArray());

    List<Object> expiryParameters = new ArrayList<>();
    expiryParameters.add(Date.valueOf(today));
    expiryParameters.add(Date.valueOf(today.plusDays(Math.max(0, expiryAlertDays))));
    expiryParameters.add(Date.valueOf(today));
    String expiryBranches = branchCondition("sl.branch_uid", branchUids, expiryParameters);
    Map<String, Object> expiry =
        jdbc.queryForMap(
            "select count(distinct b.id) filter (where b.expiry_date >= ? and b.expiry_date <= ?)"
                + " as expiring_soon,"
                + " count(distinct b.id) filter (where b.expiry_date < ?) as expired"
                + " from inventory.stock_levels sl join inventory.batches b on b.id = sl.batch_id"
                + " where sl.quantity > 0 and "
                + expiryBranches,
            expiryParameters.toArray());
    return new StockSummaryView(
        number(figures.get("products")),
        number(figures.get("in_stock")),
        number(figures.get("low_stock")),
        number(figures.get("out_of_stock")),
        number(expiry.get("expiring_soon")),
        number(expiry.get("expired")),
        Amounts.money((BigDecimal) figures.get("cost_value")),
        Amounts.money((BigDecimal) figures.get("retail_value")));
  }

  @Override
  public List<BatchView> batches(UUID productUid) {
    UUID branch = ExecutionContext.requireBranch();
    LocalDate today = today();
    return levels.inStock(branch, productUid).stream()
        .map(
            level ->
                new BatchView(
                    level.getBatch().getUid(),
                    level.getProductUid(),
                    level.getBatch().getBatchNumber(),
                    level.getBatch().getExpiryDate(),
                    level.getQuantity(),
                    level.getBatch().getUnitCost(),
                    level.getBatch().getExpiryDate() == null
                        ? null
                        : ChronoUnit.DAYS.between(today, level.getBatch().getExpiryDate()),
                    level.getBatch().expiredOn(today),
                    level.getBatch().isQuarantined()))
        .toList();
  }

  @Override
  public List<SerialUnitView> serials(UUID productUid, SerialStatus status) {
    UUID branch = ExecutionContext.requireBranch();
    return units.atBranch(branch, productUid, status).stream()
        .map(
            unit ->
                new SerialUnitView(
                    unit.getUid(),
                    unit.getProductUid(),
                    unit.getSerialNumber(),
                    unit.getBatch().getUid(),
                    unit.getBatch().getBatchNumber(),
                    unit.getBranchUid(),
                    unit.getStatus(),
                    unit.getSaleReference(),
                    unit.getSoldAt(),
                    unit.getCreatedAt()))
        .toList();
  }

  @Override
  public Page<ExpiringBatchView> expiring(Set<UUID> branchUids, int withinDays, PageQuery query) {
    if (withinDays < 0 || withinDays > 3650) {
      throw Refusal.invalid(
          "invalid_within_days", "withinDays must be between 0 and 3650.", "withinDays");
    }
    LocalDate today = today();
    List<Object> parameters = new ArrayList<>();
    String branches = branchCondition("sl.branch_uid", branchUids, parameters);
    parameters.add(Date.valueOf(today.plusDays(withinDays)));
    parameters.add(query.limit() + 1);
    parameters.add(query.offset());
    List<ExpiringBatchView> rows =
        jdbc.query(
            "select b.uid as batch_uid, b.product_uid, coalesce(p.name, '') as product_name,"
                + " b.batch_number, b.expiry_date, sum(sl.quantity) as quantity,"
                + " sum(sl.quantity * b.unit_cost) as cost_value"
                + " from inventory.stock_levels sl"
                + " join inventory.batches b on b.id = sl.batch_id"
                + " left join inventory.products p on p.product_uid = b.product_uid"
                + " where sl.quantity > 0 and "
                + branches
                + " and b.expiry_date <= ?"
                + " group by b.id, b.uid, b.product_uid, p.name, b.batch_number, b.expiry_date"
                + " order by b.expiry_date, b.id limit ? offset ?",
            (rs, row) -> {
              LocalDate expiryDate = date(rs, "expiry_date");
              return new ExpiringBatchView(
                  rs.getObject("batch_uid", UUID.class),
                  rs.getObject("product_uid", UUID.class),
                  rs.getString("product_name"),
                  rs.getString("batch_number"),
                  expiryDate,
                  ChronoUnit.DAYS.between(today, expiryDate),
                  rs.getInt("quantity"),
                  Amounts.money(rs.getBigDecimal("cost_value")));
            },
            parameters.toArray());
    return Page.offset(rows, query);
  }

  @Override
  public Page<MovementView> movements(UUID productUid, MovementType type, PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    Specification<Movement> filter =
        Specs.<Movement>equal("branchUid", branch)
            .and(Specs.equal("productUid", productUid))
            .and(Specs.equal("type", type));
    return Keyset.page(movements, filter, query, views::movement);
  }

  @Override
  public Map<UUID, Integer> onHand(Collection<UUID> productUids) {
    UUID branch = ExecutionContext.requireBranch();
    Map<UUID, Integer> quantities = new HashMap<>();
    if (productUids == null || productUids.isEmpty()) {
      return quantities;
    }
    productUids.forEach(uid -> quantities.put(uid, 0));
    for (Object[] row : levels.sellableQuantities(branch, productUids, today())) {
      quantities.put((UUID) row[0], ((Number) row[1]).intValue());
    }
    return quantities;
  }

  @Override
  public Map<UUID, Integer> quarantined(Collection<UUID> productUids) {
    UUID branch = ExecutionContext.requireBranch();
    Map<UUID, Integer> quantities = new HashMap<>();
    if (productUids == null || productUids.isEmpty()) {
      return quantities;
    }
    productUids.forEach(uid -> quantities.put(uid, 0));
    for (Object[] row : levels.quarantinedQuantities(branch, productUids)) {
      quantities.put((UUID) row[0], ((Number) row[1]).intValue());
    }
    return quantities;
  }

  @Override
  public long unitsHeld(UUID productUid) {
    return levels.quantityEverywhere(productUid) + transfers.quantityInTransit(productUid);
  }

  @Override
  public Page<TransferView> transfers(String direction, PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    Specification<Transfer> filter;
    if (direction == null || direction.isBlank()) {
      filter =
          (root, q, cb) ->
              cb.or(
                  cb.equal(root.get("fromBranchUid"), branch),
                  cb.equal(root.get("toBranchUid"), branch));
    } else if ("OUTGOING".equalsIgnoreCase(direction.trim())) {
      filter = Specs.equal("fromBranchUid", branch);
    } else if ("INCOMING".equalsIgnoreCase(direction.trim())) {
      filter = Specs.equal("toBranchUid", branch);
    } else {
      throw Refusal.invalid("invalid_direction", "direction is OUTGOING or INCOMING.", "direction");
    }
    Map<UUID, String> names = new HashMap<>();
    return Keyset.page(transfers, filter, query, transfer -> views.transfer(transfer, names));
  }

  /**
   * {@code column in (...)} for the given branches, {@code true} for all, {@code false} for none.
   */
  private static String branchCondition(
      String column, Set<UUID> branchUids, List<Object> parameters) {
    if (branchUids == null) {
      return "true";
    }
    if (branchUids.isEmpty()) {
      return "false";
    }
    StringBuilder condition = new StringBuilder(column).append(" in (");
    int i = 0;
    for (UUID branch : branchUids) {
      condition.append(i++ == 0 ? "?" : ", ?");
      parameters.add(branch);
    }
    return condition.append(")").toString();
  }

  private static LocalDate date(ResultSet rs, String column) throws SQLException {
    Date date = rs.getDate(column);
    return date == null ? null : date.toLocalDate();
  }

  private static long number(Object value) {
    return value == null ? 0 : ((Number) value).longValue();
  }

  private LocalDate today() {
    return calendar.today();
  }
}
