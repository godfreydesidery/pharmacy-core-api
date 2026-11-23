package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.inventory.domain.Batch;
import tz.co.pharmacy.modules.inventory.domain.ProductRecord;
import tz.co.pharmacy.modules.inventory.domain.StockCount;
import tz.co.pharmacy.modules.inventory.domain.StockCountLine;
import tz.co.pharmacy.modules.inventory.domain.StockLevel;
import tz.co.pharmacy.modules.inventory.persistence.BatchRepository;
import tz.co.pharmacy.modules.inventory.persistence.ProductRecordRepository;
import tz.co.pharmacy.modules.inventory.persistence.StockCountRepository;
import tz.co.pharmacy.modules.inventory.persistence.StockLevelRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustmentReason;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockCounts;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
@RequiredArgsConstructor
class StockCountsImpl implements StockCounts {

  private final StockCountRepository counts;
  private final StockLevelRepository levels;
  private final ProductRecordRepository products;
  private final StockService stock;
  private final BatchRepository batches;
  private final DocumentNumbers documentNumbers;
  private final Clock clock;

  @Override
  @Transactional
  public StockCountView start(String note) {
    UUID branch = ExecutionContext.requireBranch();
    StockCount count =
        new StockCount(
            documentNumbers.format("CNT", 6), branch, Checks.optionalText(note, "note", 500));
    Map<UUID, ProductRecord> byUid =
        products.findAll().stream()
            .collect(
                Collectors.toMap(ProductRecord::getProductUid, Function.identity(), (a, b) -> a));
    // The sheet's order, the same in every view: product name, then earliest expiry, then batch.
    List<StockLevel> onHand = new ArrayList<>(levels.allOnHand(branch));
    onHand.sort(
        java.util.Comparator.comparing(
                (StockLevel l) ->
                    byUid.containsKey(l.getProductUid())
                        ? byUid.get(l.getProductUid()).getName()
                        : "",
                String.CASE_INSENSITIVE_ORDER)
            .thenComparing(
                l -> l.getBatch().getExpiryDate(),
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
            .thenComparing(l -> l.getBatch().getBatchNumber()));
    for (StockLevel level : onHand) {
      ProductRecord product = byUid.get(level.getProductUid());
      if (product == null || product.serialTracked()) {
        continue;
      }
      count.addLine(
          new StockCountLine(
              product.getProductUid(),
              product.getName(),
              level.getBatch().getUid(),
              level.getBatch().getBatchNumber(),
              level.getBatch().getExpiryDate(),
              level.getBatch().getUnitCost(),
              level.getQuantity()));
    }
    return view(counts.save(count), true);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<StockCountView> list(PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    Map<UUID, Integer> now = levelsNow(branch);
    return Keyset.page(
        counts,
        Specs.equal("branchUid", branch),
        query,
        c -> view(c, false, "OPEN".equals(c.getStatus()) ? now : null));
  }

  @Override
  @Transactional(readOnly = true)
  public StockCountView get(UUID uid) {
    return view(count(uid), true);
  }

  @Override
  @Transactional
  public StockCountView record(UUID uid, List<CountEntry> entries) {
    StockCount count = open(uid);
    Map<UUID, StockCountLine> lines =
        count.getLines().stream().collect(Collectors.toMap(StockCountLine::getUid, l -> l));
    List<CountEntry> given = entries == null ? List.of() : entries;
    for (int i = 0; i < given.size(); i++) {
      CountEntry entry = given.get(i);
      StockCountLine line = lines.get(entry.lineUid());
      if (line == null) {
        throw Refusal.invalid(
            "line_not_found", "The line is not on this count.", "entries[" + i + "]");
      }
      if (entry.counted() != null && entry.counted() < 0) {
        throw Refusal.invalid(
            "invalid_quantity", "A count cannot be negative.", "entries[" + i + "].counted");
      }
      line.counted(entry.counted());
    }
    return view(count, true);
  }

  /**
   * MON-6: the stock kept moving while the sheet was being counted (sales, receipts, transfers), so
   * each counted line is set against the book quantity now, expected + what moved since the sheet:
   * correction = counted − (expected + moved). Ten sold during the count and counted ten fewer
   * posts nothing; a batch sold out meanwhile and counted 0 stays postable. Each level is read
   * under the product's lock, as every stock write takes it, products in a fixed order.
   */
  @Override
  @Transactional
  public StockCountView post(UUID uid) {
    StockCount count = open(uid);
    UUID branch = count.getBranchUid();
    List<StockCountLine> counted =
        count.getLines().stream()
            .filter(line -> line.getCounted() != null)
            .sorted(
                java.util.Comparator.comparing(StockCountLine::getProductUid)
                    .thenComparing(StockCountLine::getBatchUid))
            .toList();
    for (StockCountLine line : counted) {
      products.lockSnapshot(line.getProductUid());
      Batch batch =
          batches
              .findByUid(line.getBatchUid())
              .orElseThrow(() -> Refusal.invalid("batch_not_found", "No such batch."));
      int now = levels.lockAt(branch, batch).map(StockLevel::getQuantity).orElse(0);
      line.moved(now - line.getExpected());
      int difference = line.getCounted() - now;
      if (difference != 0) {
        stock.adjust(
            new AdjustStockCommand(
                line.getProductUid(),
                line.getBatchUid(),
                difference,
                AdjustmentReason.COUNT_CORRECTION,
                "Stock-take " + count.getNumber(),
                null));
      }
    }
    count.posted(clock.instant(), ExecutionContext.actor().reference());
    return view(count, true);
  }

  private StockCount open(UUID uid) {
    StockCount count = count(uid);
    if (!"OPEN".equals(count.getStatus())) {
      throw Refusal.invalid("count_posted", "This stock-take has already been posted.");
    }
    return count;
  }

  private StockCount count(UUID uid) {
    StockCount count =
        counts
            .findByUid(uid)
            .orElseThrow(() -> Refusal.notFound("count_not_found", "No such stock-take."));
    if (!count.getBranchUid().equals(ExecutionContext.requireBranch())) {
      throw Refusal.notFound("count_not_found", "No such stock-take.");
    }
    return count;
  }

  /** Product name (any case), then earliest expiry, then batch number. */
  private static final java.util.Comparator<StockCountLine> SHEET_ORDER =
      java.util.Comparator.comparing(StockCountLine::getProductName, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(
              StockCountLine::getExpiryDate,
              java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
          .thenComparing(StockCountLine::getBatchNumber);

  private StockCountView view(StockCount c, boolean withLines) {
    return view(c, withLines, "OPEN".equals(c.getStatus()) ? levelsNow(c.getBranchUid()) : null);
  }

  /** The branch's stock per batch now, to show an open count's variance as posting would. */
  private Map<UUID, Integer> levelsNow(UUID branch) {
    Map<UUID, Integer> now = new java.util.HashMap<>();
    for (Object[] row : levels.quantitiesByBatch(branch)) {
      now.put((UUID) row[0], ((Number) row[1]).intValue());
    }
    return now;
  }

  /** What moved since the sheet: recorded at posting, or the book quantity now less expected. */
  private static int moved(StockCount c, StockCountLine l, Map<UUID, Integer> now) {
    if (l.getMoved() != null) {
      return l.getMoved();
    }
    if (now == null) {
      return 0;
    }
    return now.getOrDefault(l.getBatchUid(), 0) - l.getExpected();
  }

  private static StockCountView view(StockCount c, boolean withLines, Map<UUID, Integer> now) {
    BigDecimal value = BigDecimal.ZERO;
    int counted = 0;
    List<LineView> items = new ArrayList<>();
    List<StockCountLine> ordered = new ArrayList<>(c.getLines());
    ordered.sort(SHEET_ORDER);
    for (StockCountLine l : ordered) {
      Integer variance =
          l.getCounted() == null ? null : l.getCounted() - (l.getExpected() + moved(c, l, now));
      BigDecimal lineValue =
          variance == null
              ? null
              : Amounts.money(l.getUnitCost().multiply(BigDecimal.valueOf(variance)));
      if (variance != null) {
        counted++;
        value = value.add(lineValue);
      }
      if (withLines) {
        items.add(
            new LineView(
                l.getUid(),
                l.getProductUid(),
                l.getProductName(),
                l.getBatchNumber(),
                l.getExpiryDate(),
                l.getExpected(),
                l.getCounted(),
                variance,
                lineValue));
      }
    }
    return new StockCountView(
        c.getUid(),
        c.getNumber(),
        c.getStatus(),
        c.getNote(),
        c.getLines().size(),
        counted,
        Amounts.money(value),
        c.getCreatedAt(),
        c.getCreatedBy(),
        c.getPostedAt(),
        c.getPostedBy(),
        items);
  }
}
