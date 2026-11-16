package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.inventory.domain.Batch;
import tz.co.pharmacy.modules.inventory.domain.Destruction;
import tz.co.pharmacy.modules.inventory.domain.DestructionLine;
import tz.co.pharmacy.modules.inventory.domain.ProductRecord;
import tz.co.pharmacy.modules.inventory.persistence.BatchRepository;
import tz.co.pharmacy.modules.inventory.persistence.DestructionRepository;
import tz.co.pharmacy.modules.inventory.persistence.ProductRecordRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustmentReason;
import tz.co.pharmacy.modules.inventory.ports.inbound.Destructions;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;

@Service
@RequiredArgsConstructor
class DestructionsImpl implements Destructions {

  private static final Set<String> REASONS = Set.of("EXPIRED", "DAMAGED");

  private final DestructionRepository destructions;
  private final BatchRepository batches;
  private final ProductRecordRepository products;
  private final StockService stock;
  private final InventoryViews views;
  private final DocumentNumbers documentNumbers;
  private final Clock clock;

  @Override
  @Transactional
  public DestructionView record(DestructionCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    Checks.required(command, "body");
    LocalDate today = LocalDate.now(clock);
    LocalDate on = command.destroyedOn() == null ? today : command.destroyedOn();
    if (on.isAfter(today)) {
      throw Refusal.invalid(
          "invalid_date", "A destruction cannot be dated in the future.", "destroyedOn");
    }
    List<LineCommand> lines = Checks.notEmpty(command.lines(), "lines");
    Destruction destruction =
        new Destruction(
            documentNumbers.format("DST", 6),
            branch,
            on,
            Checks.text(command.method(), "method", 200),
            Checks.text(command.witnessName(), "witnessName", 200),
            Checks.optionalText(command.witnessTitle(), "witnessTitle", 200),
            Checks.optionalText(command.certificateNumber(), "certificateNumber", 100),
            Checks.optionalText(command.note(), "note", 500));
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      LineCommand line = lines.get(i);
      String field = "lines[" + i + "]";
      UUID batchUid = Checks.required(line.batchUid(), field + ".batchUid");
      int quantity =
          Checks.positive(line.quantity() == null ? 0 : line.quantity(), field + ".quantity");
      String reason = Checks.text(line.reason(), field + ".reason", 20).toUpperCase();
      if (!REASONS.contains(reason)) {
        throw Refusal.invalid(
            "invalid_reason", "A line is destroyed as EXPIRED or DAMAGED.", field + ".reason");
      }
      if (!seen.add(batchUid)) {
        throw Refusal.invalid("duplicate_batch", "Each batch appears once.", field + ".batchUid");
      }
      Batch batch =
          batches
              .findByUid(batchUid)
              .orElseThrow(
                  () -> Refusal.invalid("batch_not_found", "No such batch.", field + ".batchUid"));
      String name =
          products.findByProductUid(batch.getProductUid()).map(ProductRecord::getName).orElse("");
      try {
        stock.adjust(
            new AdjustStockCommand(
                batch.getProductUid(),
                batchUid,
                -quantity,
                "EXPIRED".equals(reason)
                    ? AdjustmentReason.EXPIRED_WRITE_OFF
                    : AdjustmentReason.DAMAGE,
                "Destruction " + destruction.getNumber(),
                null));
      } catch (Refusal refusal) {
        throw refusal.about(field + ".quantity");
      }
      destruction.addLine(
          new DestructionLine(
              batch.getProductUid(),
              name,
              batchUid,
              batch.getBatchNumber(),
              batch.getExpiryDate(),
              reason,
              quantity,
              batch.getUnitCost()));
    }
    return view(destructions.save(destruction));
  }

  @Override
  @Transactional(readOnly = true)
  public List<DestructionView> list() {
    return destructions
        .findTop100ByBranchUidOrderByIdDesc(ExecutionContext.requireBranch())
        .stream()
        .map(this::view)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public DestructionView get(UUID uid) {
    Destruction destruction =
        destructions
            .findByUid(uid)
            .filter(d -> d.getBranchUid().equals(ExecutionContext.requireBranch()))
            .orElseThrow(() -> Refusal.notFound("destruction_not_found", "No such destruction."));
    return view(destruction);
  }

  private DestructionView view(Destruction d) {
    return new DestructionView(
        d.getUid(),
        d.getNumber(),
        d.getBranchUid(),
        views.branchName(d.getBranchUid()),
        d.getDestroyedOn(),
        d.getMethod(),
        d.getWitnessName(),
        d.getWitnessTitle(),
        d.getCertificateNumber(),
        d.getNote(),
        d.getTotalCost(),
        d.getCreatedAt(),
        d.getCreatedBy(),
        d.getLines().stream()
            .map(
                l ->
                    new LineView(
                        l.getProductUid(),
                        l.getProductName(),
                        l.getBatchUid(),
                        l.getBatchNumber(),
                        l.getExpiryDate(),
                        l.getReason(),
                        l.getQuantity(),
                        l.getUnitCost(),
                        Amounts.money(
                            l.getUnitCost().multiply(BigDecimal.valueOf(l.getQuantity())))))
            .toList());
  }
}
