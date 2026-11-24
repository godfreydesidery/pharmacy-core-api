package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
import tz.co.pharmacy.modules.inventory.persistence.StockLevelRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustmentReason;
import tz.co.pharmacy.modules.inventory.ports.inbound.Destructions;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

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
  private final TenantCalendar calendar;
  private final StockControls controls;
  private final StockLevelRepository levels;

  @Override
  @Transactional
  public DestructionView record(DestructionCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    Checks.required(command, "body");
    LocalDate today = calendar.today();
    LocalDate on = command.destroyedOn() == null ? today : command.destroyedOn();
    if (on.isAfter(today)) {
      throw Refusal.invalid(
          "invalid_date", "A destruction cannot be dated in the future.", "destroyedOn");
    }
    List<LineCommand> lines = Checks.notEmpty(command.lines(), "lines");
    String note = StockControls.requireNote(command.note(), "note");
    String number = documentNumbers.format("DST", 6);
    // Each line is checked before anything is written off: its batch, reason and value at cost.
    List<Planned> planned = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    BigDecimal total = BigDecimal.ZERO;
    boolean controlled = false;
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
      // "Expired" only for a batch past its expiry on the day it is destroyed (local date).
      if ("EXPIRED".equals(reason)
          && (batch.getExpiryDate() == null || !batch.getExpiryDate().isBefore(on))) {
        throw Refusal.invalid(
                "batch_not_expired",
                "Batch "
                    + batch.getBatchNumber()
                    + " of "
                    + name
                    + (batch.getExpiryDate() == null
                        ? " has no expiry date"
                        : " expires on " + batch.getExpiryDate())
                    + "; destroy it as DAMAGED, or wait until it has expired.",
                field + ".reason")
            .with("batch", batch.getBatchNumber())
            .with("product", name)
            .with("expiry", batch.getExpiryDate());
      }
      // Short stock is refused before a manager is asked to approve it.
      int held = (int) levels.quantityOfBatchAt(branch, batch);
      if (held < quantity) {
        throw Refusal.invalid(
                "insufficient_stock",
                "Only "
                    + held
                    + " of "
                    + name
                    + " batch "
                    + batch.getBatchNumber()
                    + " are in stock at this branch.")
            .with("product", name)
            .with("batch", batch.getBatchNumber())
            .with("available", held)
            .with("requested", quantity)
            .about(field + ".quantity");
      }
      planned.add(new Planned(field, batch, name, reason, quantity));
      total = total.add(batch.getUnitCost().multiply(BigDecimal.valueOf(quantity)));
      controlled |= controls.controlled(batch.getProductUid());
    }
    // FRD-5: a controlled medicine always, other stock above the limit, needs a manager; the
    // manager who approves is the destruction's witness.
    Optional<StockControls.Approver> approver = controls.approveWriteOff(controlled, total, number);
    String typedWitness =
        approver.isPresent()
            ? Checks.optionalText(command.witnessName(), "witnessName", 200)
            : Checks.text(command.witnessName(), "witnessName", 200);
    Destruction destruction =
        new Destruction(
            number,
            branch,
            on,
            Checks.text(command.method(), "method", 200),
            typedWitness,
            Checks.optionalText(command.witnessTitle(), "witnessTitle", 200),
            Checks.optionalText(command.certificateNumber(), "certificateNumber", 100),
            note);
    destruction.recordedBy(ExecutionContext.actor().name());
    approver.ifPresent(a -> destruction.approvedBy(a.name(), a.uid()));
    controls.covered(
        () -> {
          for (Planned line : planned) {
            Batch batch = line.batch();
            try {
              stock.adjust(
                  new AdjustStockCommand(
                      batch.getProductUid(),
                      batch.getUid(),
                      -line.quantity(),
                      "EXPIRED".equals(line.reason())
                          ? AdjustmentReason.EXPIRED_WRITE_OFF
                          : AdjustmentReason.DAMAGE,
                      "Destruction " + destruction.getNumber(),
                      null));
            } catch (Refusal refusal) {
              throw refusal.about(line.field() + ".quantity");
            }
            destruction.addLine(
                new DestructionLine(
                    batch.getProductUid(),
                    line.productName(),
                    batch.getUid(),
                    batch.getBatchNumber(),
                    batch.getExpiryDate(),
                    line.reason(),
                    line.quantity(),
                    batch.getUnitCost()));
          }
          return null;
        });
    return view(destructions.save(destruction));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<DestructionView> list(PageQuery query) {
    return Keyset.page(
        destructions,
        Specs.equal("branchUid", ExecutionContext.requireBranch()),
        query,
        this::view);
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

  private record Planned(
      String field, Batch batch, String productName, String reason, int quantity) {}

  private DestructionView view(Destruction d) {
    return new DestructionView(
        d.getUid(),
        d.getNumber(),
        d.getBranchUid(),
        views.branchName(d.getBranchUid()),
        d.getDestroyedOn(),
        d.getMethod(),
        d.getApprovedByName() != null ? d.getApprovedByName() : d.getWitnessName(),
        d.getApprovedByName() != null,
        d.getApprovedByUid(),
        d.getWitnessName(),
        d.getWitnessTitle(),
        d.getCertificateNumber(),
        d.getNote(),
        d.getTotalCost(),
        d.getCreatedAt(),
        d.getCreatedBy(),
        d.getCreatedByName() == null ? d.getCreatedBy() : d.getCreatedByName(),
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
