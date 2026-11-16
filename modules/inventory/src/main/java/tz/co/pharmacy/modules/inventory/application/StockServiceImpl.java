package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.inventory.domain.Batch;
import tz.co.pharmacy.modules.inventory.domain.Movement;
import tz.co.pharmacy.modules.inventory.domain.ProductRecord;
import tz.co.pharmacy.modules.inventory.domain.SerialUnit;
import tz.co.pharmacy.modules.inventory.domain.StockLevel;
import tz.co.pharmacy.modules.inventory.domain.Transfer;
import tz.co.pharmacy.modules.inventory.domain.TransferLine;
import tz.co.pharmacy.modules.inventory.events.StockAdjusted;
import tz.co.pharmacy.modules.inventory.events.StockLow;
import tz.co.pharmacy.modules.inventory.persistence.BatchRepository;
import tz.co.pharmacy.modules.inventory.persistence.MovementRepository;
import tz.co.pharmacy.modules.inventory.persistence.ProductRecordRepository;
import tz.co.pharmacy.modules.inventory.persistence.SerialUnitRepository;
import tz.co.pharmacy.modules.inventory.persistence.StockLevelRepository;
import tz.co.pharmacy.modules.inventory.persistence.TransferRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustmentReason;
import tz.co.pharmacy.modules.inventory.ports.inbound.Allocation;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementView;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshot;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshots;
import tz.co.pharmacy.modules.inventory.ports.inbound.ReceiveStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.SerialStatus;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferLineCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferStatus;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferView;
import tz.co.pharmacy.modules.inventory.ports.outbound.ProductCatalogue;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;

/**
 * Every write to stock. Each change of a stock level records a movement with the product's stock at
 * the branch after it. Sales and transfers take stock from rows locked for the transaction:
 * earliest expiry first (FEFO) and never from an expired batch, or first in, first out (FIFO) for
 * batches without expiry. A SERIAL product is also counted unit by unit: every unit it takes or
 * returns changes its {@link SerialUnit} too.
 */
@Service
@RequiredArgsConstructor
class StockServiceImpl implements StockService, ProductSnapshots {

  /** The batch of a QUANTITY product, which has no batches of its own. */
  static final String IMPLICIT_BATCH = "-";

  private final ProductRecordRepository products;
  private final BatchRepository batches;
  private final StockLevelRepository levels;
  private final MovementRepository movements;
  private final TransferRepository transfers;
  private final SerialUnitRepository units;
  private final ProductCatalogue catalogue;
  private final DocumentNumbers documentNumbers;
  private final DomainEventPublisher events;
  private final InventoryViews views;
  private final Clock clock;
  private final TenantCalendar calendar;

  @Override
  @Transactional
  public void upsert(ProductSnapshot snapshot) {
    products
        .findByProductUid(snapshot.productUid())
        .ifPresentOrElse(
            record -> record.apply(snapshot), () -> products.save(new ProductRecord(snapshot)));
  }

  @Override
  @Transactional
  public void receive(ReceiveStockCommand command) {
    stockIn(command);
  }

  @Override
  @Transactional
  public MovementView openingStock(ReceiveStockCommand command) {
    return views.movement(
        stockIn(
            new ReceiveStockCommand(
                command.productUid(),
                command.batchNumber(),
                command.expiryDate(),
                command.quantity(),
                command.unitCost(),
                MovementType.OPENING,
                command.reference() == null ? "OPENING" : command.reference(),
                command.serialNumbers())));
  }

  @Override
  @Transactional
  public List<Allocation> allocate(UUID productUid, int quantity, String reference) {
    return allocate(productUid, quantity, reference, null);
  }

  @Override
  @Transactional
  public List<Allocation> allocate(
      UUID productUid, int quantity, String reference, List<String> serialNumbers) {
    UUID branch = ExecutionContext.requireBranch();
    Checks.required(productUid, "productUid");
    Checks.positive(quantity, "quantity");
    ProductRecord product = product(productUid);
    int before = (int) levels.quantityAt(branch, productUid);
    List<Allocation> taken =
        product.serialTracked()
            ? takeUnits(
                branch,
                product,
                quantity,
                SerialRules.normalise(serialNumbers, "serialNumbers"),
                MovementType.SALE,
                reference,
                before)
            : take(branch, product, quantity, MovementType.SALE, reference, before);
    signalLow(product, before, before - quantity);
    return taken;
  }

  @Override
  @Transactional
  public void restore(UUID batchUid, int quantity, String reference) {
    restore(batchUid, quantity, reference, null);
  }

  @Override
  @Transactional
  public void restore(UUID batchUid, int quantity, String reference, List<String> serialNumbers) {
    UUID branch = ExecutionContext.requireBranch();
    Checks.positive(quantity, "quantity");
    Batch batch = batch(Checks.required(batchUid, "batchUid"));
    ProductRecord product = product(batch.getProductUid());
    List<String> serials = SerialRules.normalise(serialNumbers, "serialNumbers");
    if (product.serialTracked()) {
      SerialRules.requireCount(serials, quantity, "serialNumbers");
      for (SerialUnit unit : named(product, serials)) {
        if (unit.getStatus() != SerialStatus.SOLD
            || !unit.getBatch().getUid().equals(batch.getUid())) {
          throw Refusal.invalid(
              "serial_not_sold",
              "Serial " + unit.getSerialNumber() + " was not sold from this batch.",
              "serialNumbers");
        }
        unit.restock(branch);
      }
    }
    int before = (int) levels.quantityAt(branch, batch.getProductUid());
    StockLevel level = levelFor(branch, batch);
    level.change(quantity);
    movements.save(
        new Movement(
            branch,
            product.getName(),
            batch,
            MovementType.SALE_RETURN,
            quantity,
            before + quantity,
            reference,
            null,
            null,
            serials));
  }

  @Override
  @Transactional
  public Allocation returnToSupplier(UUID batchUid, int quantity, String reference) {
    UUID branch = ExecutionContext.requireBranch();
    Checks.positive(quantity, "quantity");
    Batch batch = batch(Checks.required(batchUid, "batchUid"));
    ProductRecord product = product(batch.getProductUid());
    int before = (int) levels.quantityAt(branch, batch.getProductUid());
    StockLevel level = levels.lockAt(branch, batch).orElse(null);
    int held = level == null ? 0 : level.getQuantity();
    if (held < quantity) {
      throw Refusal.invalid(
              "insufficient_stock",
              "Only "
                  + held
                  + " of "
                  + product.getName()
                  + " batch "
                  + batch.getBatchNumber()
                  + " are in stock at this branch.")
          .about("quantity");
    }
    List<String> serials = List.of();
    if (product.serialTracked()) {
      List<SerialUnit> chosen =
          units.lockAt(branch, product.getProductUid(), SerialStatus.IN_STOCK).stream()
              .filter(unit -> unit.getBatch().getUid().equals(batch.getUid()))
              .limit(quantity)
              .toList();
      if (chosen.size() < quantity) {
        throw Refusal.invalid(
                "insufficient_stock",
                "Only " + chosen.size() + " units of " + product.getName() + " are in stock here.")
            .about("quantity");
      }
      chosen.forEach(SerialUnit::writeOff);
      serials = chosen.stream().map(SerialUnit::getSerialNumber).toList();
    }
    level.change(-quantity);
    int after = before - quantity;
    movements.save(
        new Movement(
            branch,
            product.getName(),
            batch,
            MovementType.SUPPLIER_RETURN,
            -quantity,
            after,
            reference,
            null,
            null,
            serials));
    signalLow(product, before, after);
    return new Allocation(
        batch.getUid(),
        batch.getBatchNumber(),
        batch.getExpiryDate(),
        quantity,
        batch.getUnitCost(),
        serials);
  }

  @Override
  @Transactional
  public MovementView adjust(AdjustStockCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    UUID productUid = Checks.required(command.productUid(), "productUid");
    Batch batch = batch(Checks.required(command.batchUid(), "batchUid"));
    if (!batch.getProductUid().equals(productUid)) {
      throw Refusal.invalid(
          "batch_not_of_product", "The batch is not a batch of this product.", "batchUid");
    }
    AdjustmentReason reason = Checks.required(command.reasonCode(), "reasonCode");
    int change = command.quantityChange();
    if (change == 0) {
      throw Refusal.invalid(
          "invalid_quantity_change", "quantityChange must not be zero.", "quantityChange");
    }
    String note = Checks.optionalText(command.note(), "note", 500);
    ProductRecord product = product(productUid);
    int before = (int) levels.quantityAt(branch, productUid);
    StockLevel level =
        levels.lockAt(branch, batch).orElseGet(() -> levels.save(new StockLevel(branch, batch)));
    if (level.getQuantity() + change < 0) {
      throw Refusal.invalid(
              "insufficient_stock",
              "Only "
                  + level.getQuantity()
                  + " of "
                  + product.getName()
                  + " batch "
                  + batch.getBatchNumber()
                  + " are in stock at this branch.")
          .about("quantityChange");
    }
    List<String> serials = SerialRules.normalise(command.serialNumbers(), "serialNumbers");
    if (product.serialTracked()) {
      serials = adjustUnits(branch, product, batch, change, serials);
    }
    level.change(change);
    int after = before + change;
    Movement movement =
        movements.save(
            new Movement(
                branch,
                product.getName(),
                batch,
                MovementType.ADJUSTMENT,
                change,
                after,
                "ADJUSTMENT",
                reason,
                note,
                serials));
    events.publish(
        "stock.adjusted",
        meta ->
            new StockAdjusted(
                meta,
                productUid,
                product.getName(),
                batch.getUid(),
                change,
                reason.name(),
                after,
                movement.getUid(),
                Amounts.money(batch.getUnitCost().multiply(BigDecimal.valueOf(change)))));
    signalLow(product, before, after);
    return views.movement(movement);
  }

  /**
   * The units an adjustment of a SERIAL product adds or removes: an increase names its new units; a
   * decrease takes the named units, or the batch's oldest in stock, out as written off.
   */
  private List<String> adjustUnits(
      UUID branch, ProductRecord product, Batch batch, int change, List<String> serials) {
    if (change > 0) {
      SerialRules.requireCount(serials, change, "serialNumbers");
      requireNew(product, serials);
      serials.forEach(serial -> units.save(new SerialUnit(batch, serial, branch)));
      return serials;
    }
    int count = -change;
    List<SerialUnit> chosen;
    if (serials.isEmpty()) {
      chosen =
          units.lockAt(branch, product.getProductUid(), SerialStatus.IN_STOCK).stream()
              .filter(unit -> unit.getBatch().getUid().equals(batch.getUid()))
              .limit(count)
              .toList();
    } else {
      SerialRules.requireCount(serials, count, "serialNumbers");
      chosen = inStockAt(branch, product, serials);
      if (chosen.stream().anyMatch(unit -> !unit.getBatch().getUid().equals(batch.getUid()))) {
        throw Refusal.invalid(
            "serial_not_of_batch",
            "Each serial must belong to the batch adjusted.",
            "serialNumbers");
      }
    }
    if (chosen.size() < count) {
      throw Refusal.invalid(
              "insufficient_stock",
              "Only " + chosen.size() + " units of " + product.getName() + " are in stock here.")
          .about("quantityChange");
    }
    chosen.forEach(SerialUnit::writeOff);
    return chosen.stream().map(SerialUnit::getSerialNumber).toList();
  }

  @Override
  @Transactional
  public TransferView dispatch(TransferCommand command) {
    UUID from = ExecutionContext.requireBranch();
    UUID to = Checks.required(command.toBranchUid(), "toBranchUid");
    if (from.equals(to)) {
      throw Refusal.invalid("same_branch", "A transfer goes to another branch.", "toBranchUid");
    }
    if (views.branchName(to) == null) {
      throw Refusal.invalid("branch_not_found", "No such branch.", "toBranchUid");
    }
    List<TransferLineCommand> lines = Checks.notEmpty(command.lines(), "lines");
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      TransferLineCommand line = lines.get(i);
      Checks.required(line.productUid(), "lines[" + i + "].productUid");
      Checks.positive(line.quantity(), "lines[" + i + "].quantity");
      if (!seen.add(line.productUid())) {
        throw Refusal.invalid(
            "duplicate_product", "Each product appears once.", "lines[" + i + "].productUid");
      }
    }
    Transfer transfer =
        new Transfer(
            documentNumbers.format("TRF", 6),
            from,
            to,
            Checks.optionalText(command.note(), "note", 500));
    for (int i = 0; i < lines.size(); i++) {
      TransferLineCommand line = lines.get(i);
      String field = "lines[" + i + "]";
      ProductRecord product = product(line.productUid());
      int before = (int) levels.quantityAt(from, line.productUid());
      List<Allocation> taken;
      try {
        taken =
            product.serialTracked()
                ? takeUnits(
                    from,
                    product,
                    line.quantity(),
                    SerialRules.normalise(line.serialNumbers(), field + ".serialNumbers"),
                    MovementType.TRANSFER_OUT,
                    transfer.getNumber(),
                    before)
                : take(
                    from,
                    product,
                    line.quantity(),
                    MovementType.TRANSFER_OUT,
                    transfer.getNumber(),
                    before);
      } catch (Refusal refusal) {
        throw refusal.field() == null
            ? refusal.about(field + ".quantity")
            : refusal.field().startsWith("lines[")
                ? refusal
                : refusal.about(field + "." + refusal.field());
      }
      for (Allocation allocation : taken) {
        transfer.addLine(
            product.getProductUid(),
            product.getName(),
            allocation.batchUid(),
            allocation.batchNumber(),
            allocation.expiryDate(),
            allocation.quantity(),
            allocation.serialNumbers());
      }
      signalLow(product, before, before - line.quantity());
    }
    return views.transfer(transfers.save(transfer));
  }

  @Override
  @Transactional
  public TransferView receiveTransfer(UUID transferUid) {
    UUID branch = ExecutionContext.requireBranch();
    Transfer transfer =
        transfers
            .lockByUid(transferUid)
            .orElseThrow(() -> Refusal.notFound("transfer_not_found", "No such transfer."));
    if (!transfer.getToBranchUid().equals(branch)) {
      throw Refusal.forbidden(
          "not_destination", "Only the destination branch receives a transfer.");
    }
    if (transfer.getStatus() != TransferStatus.IN_TRANSIT) {
      throw Refusal.conflict("already_received", "The transfer has been received already.");
    }
    for (TransferLine line : transfer.getLines()) {
      Batch batch = batch(line.getBatchUid());
      if (!line.getSerialNumbers().isEmpty()) {
        units
            .lockNamed(line.getProductUid(), line.getSerialNumbers())
            .forEach(unit -> unit.arriveAt(branch));
      }
      int before = (int) levels.quantityAt(branch, line.getProductUid());
      levelFor(branch, batch).change(line.getQuantity());
      movements.save(
          new Movement(
              branch,
              line.getProductName(),
              batch,
              MovementType.TRANSFER_IN,
              line.getQuantity(),
              before + line.getQuantity(),
              transfer.getNumber(),
              null,
              null,
              line.getSerialNumbers()));
    }
    transfer.markReceived();
    return views.transfer(transfer);
  }

  /**
   * Stock in at the current branch: the batch is found or made, units of a SERIAL product are
   * recorded, and a movement saved. A product tracked by batch and expiry needs its expiry; one
   * tracked otherwise has none, and a QUANTITY product has one implicit batch.
   */
  private Movement stockIn(ReceiveStockCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    UUID productUid = Checks.required(command.productUid(), "productUid");
    int quantity = Checks.positive(command.quantity(), "quantity");
    BigDecimal unitCost = Checks.notNegative(command.unitCost(), "unitCost");
    MovementType type = command.type() == null ? MovementType.RECEIPT : command.type();
    if (type != MovementType.RECEIPT && type != MovementType.OPENING) {
      throw Refusal.invalid("invalid_type", "Stock comes in as OPENING or RECEIPT.", "type");
    }
    ProductRecord product = product(productUid);
    String batchNumber;
    if (product.quantityOnly()) {
      batchNumber = IMPLICIT_BATCH;
    } else if (product.serialTracked()) {
      String given = Checks.optionalText(command.batchNumber(), "batchNumber", 64);
      batchNumber = given == null ? IMPLICIT_BATCH : given;
    } else {
      batchNumber = Checks.text(command.batchNumber(), "batchNumber", 64);
    }
    LocalDate expiry = null;
    if (product.expiryTracked()) {
      expiry = Checks.required(command.expiryDate(), "expiryDate");
      if (expiry.isBefore(today())) {
        throw Refusal.invalid(
            "batch_expired",
            "The batch expired on " + expiry + "; it cannot be stocked.",
            "expiryDate");
      }
    }
    List<String> serials = SerialRules.normalise(command.serialNumbers(), "serialNumbers");
    if (product.serialTracked()) {
      SerialRules.requireCount(serials, quantity, "serialNumbers");
      requireNew(product, serials);
    } else if (!serials.isEmpty()) {
      throw Refusal.invalid(
          "serials_not_tracked",
          product.getName() + " is not tracked by serial number.",
          "serialNumbers");
    }
    LocalDate batchExpiry = expiry;
    Batch batch =
        batches
            .findByProductUidAndBatchNumber(productUid, batchNumber)
            .orElseGet(
                () -> batches.save(new Batch(productUid, batchNumber, batchExpiry, unitCost)));
    if (product.expiryTracked() && !expiry.equals(batch.getExpiryDate())) {
      throw Refusal.invalid(
          "batch_expiry_mismatch",
          "Batch " + batchNumber + " is recorded with expiry " + batch.getExpiryDate() + ".",
          "expiryDate");
    }
    serials.forEach(serial -> units.save(new SerialUnit(batch, serial, branch)));
    int before = (int) levels.quantityAt(branch, productUid);
    levelFor(branch, batch).change(quantity);
    return movements.save(
        new Movement(
            branch,
            product.getName(),
            batch,
            type,
            quantity,
            before + quantity,
            command.reference(),
            null,
            null,
            serials));
  }

  /**
   * When the stock is short only because batches are quarantined for a recall, says so (422
   * batch_quarantined) instead of reporting a plain shortage.
   */
  private void refuseQuarantined(UUID branch, ProductRecord product, int available, int quantity) {
    List<StockLevel> held = levels.quarantinedAt(branch, product.getProductUid());
    int quarantined = held.stream().mapToInt(StockLevel::getQuantity).sum();
    if (quarantined == 0 || available + quarantined < quantity) {
      return;
    }
    String batches =
        held.stream()
            .map(l -> l.getBatch().getBatchNumber())
            .distinct()
            .collect(java.util.stream.Collectors.joining(", "));
    throw Refusal.invalid(
        "batch_quarantined",
        (available == 0 ? "None" : "Only " + available)
            + " of "
            + product.getName()
            + " can be sold or moved here: batch "
            + batches
            + " is quarantined for a recall ("
            + quarantined
            + " held back).");
  }

  /**
   * Takes {@code quantity} of the product at the branch, earliest expiry first (or first in, first
   * out without expiry), recording one movement per batch; refuses when the sellable stock is
   * short.
   */
  private List<Allocation> take(
      UUID branch,
      ProductRecord product,
      int quantity,
      MovementType type,
      String reference,
      int before) {
    List<StockLevel> sellable = levels.lockSellable(branch, product.getProductUid(), today());
    int available = sellable.stream().mapToInt(StockLevel::getQuantity).sum();
    if (available < quantity) {
      refuseQuarantined(branch, product, available, quantity);
      throw Refusal.invalid(
          "insufficient_stock",
          "Only " + available + " of " + product.getName() + " are in stock at this branch.");
    }
    List<Allocation> allocations = new ArrayList<>();
    int remaining = quantity;
    int balance = before;
    for (StockLevel level : sellable) {
      if (remaining == 0) {
        break;
      }
      int portion = Math.min(remaining, level.getQuantity());
      Batch batch = level.getBatch();
      level.change(-portion);
      remaining -= portion;
      balance -= portion;
      movements.save(
          new Movement(
              branch, product.getName(), batch, type, -portion, balance, reference, null, null));
      allocations.add(
          new Allocation(
              batch.getUid(),
              batch.getBatchNumber(),
              batch.getExpiryDate(),
              portion,
              batch.getUnitCost(),
              List.of()));
    }
    return allocations;
  }

  /**
   * Takes units of a SERIAL product: the named ones, which must be in stock at the branch, or else
   * the oldest in stock. One allocation and one movement per batch the units came from; a sale
   * marks them sold, a transfer in transit.
   */
  private List<Allocation> takeUnits(
      UUID branch,
      ProductRecord product,
      int quantity,
      List<String> serials,
      MovementType type,
      String reference,
      int before) {
    List<SerialUnit> chosen;
    if (serials.isEmpty()) {
      chosen =
          units.lockAt(branch, product.getProductUid(), SerialStatus.IN_STOCK).stream()
              .filter(unit -> !unit.getBatch().isQuarantined())
              .limit(quantity)
              .toList();
      if (chosen.size() < quantity) {
        refuseQuarantined(branch, product, chosen.size(), quantity);
        throw Refusal.invalid(
            "insufficient_stock",
            "Only " + chosen.size() + " of " + product.getName() + " are in stock at this branch.");
      }
    } else {
      SerialRules.requireCount(serials, quantity, "serialNumbers");
      chosen = inStockAt(branch, product, serials);
      for (SerialUnit unit : chosen) {
        if (unit.getBatch().isQuarantined()) {
          throw Refusal.invalid(
              "batch_quarantined",
              "Serial " + unit.getSerialNumber() + " is in a recalled batch.",
              "serialNumbers");
        }
      }
    }
    Map<Batch, List<SerialUnit>> byBatch = new LinkedHashMap<>();
    chosen.forEach(
        unit -> byBatch.computeIfAbsent(unit.getBatch(), b -> new ArrayList<>()).add(unit));
    List<Allocation> allocations = new ArrayList<>();
    int balance = before;
    for (Map.Entry<Batch, List<SerialUnit>> entry : byBatch.entrySet()) {
      Batch batch = entry.getKey();
      List<SerialUnit> batchUnits = entry.getValue();
      int portion = batchUnits.size();
      StockLevel level =
          levels
              .lockAt(branch, batch)
              .filter(found -> found.getQuantity() >= portion)
              .orElseThrow(
                  () ->
                      Refusal.invalid(
                          "insufficient_stock",
                          "The stock of " + product.getName() + " does not match its units."));
      level.change(-portion);
      balance -= portion;
      List<String> numbers = batchUnits.stream().map(SerialUnit::getSerialNumber).toList();
      for (SerialUnit unit : batchUnits) {
        if (type == MovementType.SALE) {
          unit.sell(reference);
        } else {
          unit.dispatch();
        }
      }
      movements.save(
          new Movement(
              branch,
              product.getName(),
              batch,
              type,
              -portion,
              balance,
              reference,
              null,
              null,
              numbers));
      allocations.add(
          new Allocation(
              batch.getUid(),
              batch.getBatchNumber(),
              batch.getExpiryDate(),
              portion,
              batch.getUnitCost(),
              numbers));
    }
    return allocations;
  }

  /** The named units of the product, each in stock at the branch; 422 serial_not_in_stock. */
  private List<SerialUnit> inStockAt(UUID branch, ProductRecord product, List<String> serials) {
    List<SerialUnit> found = named(product, serials);
    for (SerialUnit unit : found) {
      if (unit.getStatus() != SerialStatus.IN_STOCK || !unit.getBranchUid().equals(branch)) {
        throw Refusal.invalid(
            "serial_not_in_stock",
            "Serial " + unit.getSerialNumber() + " is not in stock at this branch.",
            "serialNumbers");
      }
    }
    return found;
  }

  /** The named units of the product, in the order named; 422 serial_not_in_stock for unknown. */
  private List<SerialUnit> named(ProductRecord product, List<String> serials) {
    Map<String, SerialUnit> bySerial = new LinkedHashMap<>();
    units
        .lockNamed(product.getProductUid(), serials)
        .forEach(unit -> bySerial.put(unit.getSerialNumber(), unit));
    List<SerialUnit> found = new ArrayList<>();
    for (String serial : serials) {
      SerialUnit unit = bySerial.get(serial);
      if (unit == null) {
        throw Refusal.invalid(
            "serial_not_in_stock",
            "No unit of " + product.getName() + " has serial " + serial + ".",
            "serialNumbers");
      }
      found.add(unit);
    }
    return found;
  }

  /** 409 duplicate_serial when a unit of the product already has one of the serial numbers. */
  private void requireNew(ProductRecord product, List<String> serials) {
    if (!serials.isEmpty()
        && units.existsByProductUidAndSerialNumberIn(product.getProductUid(), serials)) {
      throw Refusal.conflict(
              "duplicate_serial",
              "A unit of " + product.getName() + " already has one of these serial numbers.")
          .about("serialNumbers");
    }
  }

  /** Publishes stock.low when the change takes the product to or below its reorder level. */
  private void signalLow(ProductRecord product, int before, int after) {
    int level = product.getReorderLevel();
    if (before > level && after <= level && after >= 0) {
      events.publish(
          "stock.low",
          meta -> new StockLow(meta, product.getProductUid(), product.getName(), after, level));
    }
  }

  private StockLevel levelFor(UUID branch, Batch batch) {
    return levels.lockAt(branch, batch).orElseGet(() -> levels.save(new StockLevel(branch, batch)));
  }

  private Batch batch(UUID batchUid) {
    return batches
        .findByUid(batchUid)
        .orElseThrow(() -> Refusal.invalid("batch_not_found", "No such batch.", "batchUid"));
  }

  /** Inventory's snapshot of the product, taken from the catalogue the first time it is needed. */
  private ProductRecord product(UUID productUid) {
    return products
        .findByProductUid(productUid)
        .orElseGet(
            () ->
                products.save(
                    new ProductRecord(
                        catalogue
                            .product(productUid)
                            .orElseThrow(
                                () ->
                                    Refusal.invalid(
                                        "product_not_found", "No such product.", "productUid")))));
  }

  private LocalDate today() {
    return calendar.today();
  }
}
