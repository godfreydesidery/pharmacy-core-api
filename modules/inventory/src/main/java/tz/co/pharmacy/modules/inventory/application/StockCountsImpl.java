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
import tz.co.pharmacy.modules.inventory.domain.ProductRecord;
import tz.co.pharmacy.modules.inventory.domain.StockCount;
import tz.co.pharmacy.modules.inventory.domain.StockCountLine;
import tz.co.pharmacy.modules.inventory.domain.StockLevel;
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
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;

@Service
@RequiredArgsConstructor
class StockCountsImpl implements StockCounts {

  private final StockCountRepository counts;
  private final StockLevelRepository levels;
  private final ProductRecordRepository products;
  private final StockService stock;
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
  public List<StockCountView> list() {
    return counts.findTop50ByBranchUidOrderByIdDesc(ExecutionContext.requireBranch()).stream()
        .map(c -> view(c, false))
        .toList();
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

  @Override
  @Transactional
  public StockCountView post(UUID uid) {
    StockCount count = open(uid);
    for (StockCountLine line : count.getLines()) {
      if (line.getCounted() == null) {
        continue;
      }
      int difference = line.getCounted() - line.getExpected();
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

  private static StockCountView view(StockCount c, boolean withLines) {
    BigDecimal value = BigDecimal.ZERO;
    int counted = 0;
    List<LineView> items = new ArrayList<>();
    List<StockCountLine> ordered = new ArrayList<>(c.getLines());
    ordered.sort(SHEET_ORDER);
    for (StockCountLine l : ordered) {
      Integer variance = l.getCounted() == null ? null : l.getCounted() - l.getExpected();
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
