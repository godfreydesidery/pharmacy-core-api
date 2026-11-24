package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.inventory.domain.Batch;
import tz.co.pharmacy.modules.inventory.domain.Movement;
import tz.co.pharmacy.modules.inventory.domain.ProductRecord;
import tz.co.pharmacy.modules.inventory.domain.StockLevel;
import tz.co.pharmacy.modules.inventory.domain.Transformation;
import tz.co.pharmacy.modules.inventory.domain.TransformationInput;
import tz.co.pharmacy.modules.inventory.events.BatchCostAveraged;
import tz.co.pharmacy.modules.inventory.persistence.BatchRepository;
import tz.co.pharmacy.modules.inventory.persistence.MovementRepository;
import tz.co.pharmacy.modules.inventory.persistence.ProductRecordRepository;
import tz.co.pharmacy.modules.inventory.persistence.StockLevelRepository;
import tz.co.pharmacy.modules.inventory.persistence.TransferRepository;
import tz.co.pharmacy.modules.inventory.persistence.TransformationRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshot;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransformationType;
import tz.co.pharmacy.modules.inventory.ports.inbound.Transformations;
import tz.co.pharmacy.modules.inventory.ports.outbound.ProductCatalogue;
import tz.co.pharmacy.modules.inventory.ports.outbound.Recipes;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;

@Service
@RequiredArgsConstructor
class TransformationsImpl implements Transformations {

  private final ProductRecordRepository products;
  private final BatchRepository batches;
  private final StockLevelRepository levels;
  private final MovementRepository movements;
  private final TransformationRepository transformations;
  private final ProductCatalogue catalogue;
  private final DocumentNumbers documentNumbers;
  private final TenantCalendar calendar;
  private final TransferRepository transfers;
  private final DomainEventPublisher events;
  private final Recipes recipes;
  private final StockControls controls;

  @Override
  @Transactional
  public TransformationView transform(TransformCommand command) {
    if (command == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    UUID branch = ExecutionContext.requireBranch();
    TransformationType type = Checks.required(command.type(), "type");
    OutputCommand out = Checks.required(command.output(), "output");
    UUID outputUid = Checks.required(out.productUid(), "output.productUid");
    int outputQuantity =
        Checks.positive(out.quantity() == null ? 0 : out.quantity(), "output.quantity");
    ProductRecord output = product(outputUid, "output.productUid");
    if (output.serialTracked()) {
      throw Refusal.invalid(
          "serial_not_supported",
          output.getName() + " is tracked by serial number and cannot be made by repacking.",
          "output.productUid");
    }
    List<InputCommand> inputs = followRecipe(type, command.inputs(), output, outputQuantity);
    refuseDowngrade(inputs, output);
    String note = Checks.optionalText(command.note(), "note", 500);
    String number = documentNumbers.format("RPK", 6);
    Transformation transformation = new Transformation(number, branch, type, note);

    BigDecimal totalCost = BigDecimal.ZERO;
    LocalDate earliest = null;
    for (int i = 0; i < inputs.size(); i++) {
      InputCommand in = inputs.get(i);
      String field = "inputs[" + i + "]";
      UUID uid = Checks.required(in.productUid(), field + ".productUid");
      int quantity =
          Checks.positive(in.quantity() == null ? 0 : in.quantity(), field + ".quantity");
      if (uid.equals(outputUid)) {
        throw Refusal.invalid(
            "same_product", "A product cannot be made from itself.", field + ".productUid");
      }
      ProductRecord product = product(uid, field + ".productUid");
      if (product.serialTracked()) {
        throw Refusal.invalid(
            "serial_not_supported",
            product.getName() + " is tracked by serial number and cannot be repacked.",
            field + ".productUid");
      }
      int before = (int) levels.quantityAt(branch, uid);
      List<StockLevel> sellable = levels.lockSellable(branch, uid, today());
      int available = sellable.stream().mapToInt(StockLevel::getQuantity).sum();
      if (available < quantity) {
        throw Refusal.invalid(
                "insufficient_stock",
                "Only " + available + " of " + product.getName() + " are in stock at this branch.")
            .with("product", product.getName())
            .with("available", available)
            .with("requested", quantity)
            .about(field + ".quantity");
      }
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
                branch,
                product.getName(),
                batch,
                MovementType.TRANSFORM_OUT,
                -portion,
                balance,
                number,
                null,
                null));
        transformation.addInput(
            new TransformationInput(
                uid,
                product.getName(),
                batch.getUid(),
                batch.getBatchNumber(),
                batch.getExpiryDate(),
                portion,
                batch.getUnitCost()));
        totalCost = totalCost.add(batch.getUnitCost().multiply(BigDecimal.valueOf(portion)));
        if (batch.getExpiryDate() != null
            && (earliest == null || batch.getExpiryDate().isBefore(earliest))) {
          earliest = batch.getExpiryDate();
        }
      }
    }

    LocalDate expiry = null;
    if (output.expiryTracked()) {
      expiry = out.expiryDate() != null ? out.expiryDate() : earliest;
      if (expiry == null) {
        throw Refusal.invalid(
            "expiry_required",
            "The inputs have no expiry; give the output's expiry date.",
            "output.expiryDate");
      }
      if (earliest != null && expiry.isAfter(earliest)) {
        throw Refusal.invalid(
            "expiry_after_inputs",
            "The output cannot outlive its inputs; the earliest input expires on " + earliest + ".",
            "output.expiryDate");
      }
      if (expiry.isBefore(today())) {
        throw Refusal.invalid("batch_expired", "That expiry date has passed.", "output.expiryDate");
      }
    }
    String given = Checks.optionalText(out.batchNumber(), "output.batchNumber", 64);
    String batchNumber =
        output.quantityOnly()
            ? "-"
            : given != null
                ? given
                : prefix(type)
                    + "-"
                    + today().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-"
                    + number.substring(number.length() - 4);
    BigDecimal unitCost =
        totalCost.divide(BigDecimal.valueOf(outputQuantity), 4, RoundingMode.HALF_UP);
    LocalDate batchExpiry = expiry;
    java.util.Optional<Batch> existing = batches.numbered(outputUid, batchNumber);
    Batch batch =
        existing.orElseGet(
            () -> batches.save(new Batch(outputUid, batchNumber, batchExpiry, unitCost)));
    if (output.expiryTracked() && !expiry.equals(batch.getExpiryDate())) {
      throw Refusal.invalid(
          "batch_expiry_mismatch",
          "Batch " + batchNumber + " is recorded with expiry " + batch.getExpiryDate() + ".",
          "output.batchNumber");
    }
    // MON-8: the output is worth exactly what its inputs cost. Into a batch already held it
    // averages, as a receipt does; the cost is kept to the cent, and the difference that leaves
    // (a few cents either way) is posted, so the stock at cost keeps matching the ledger.
    BigDecimal previousCost = existing.isPresent() ? batch.getUnitCost() : null;
    BigDecimal rounding =
        batch.receiveValue(
            existing.isPresent()
                ? levels.quantityOfBatch(batch) + transfers.quantityInTransitOfBatch(batch.getUid())
                : 0,
            outputQuantity,
            totalCost);
    int before = (int) levels.quantityAt(branch, outputUid);
    levels
        .lockAt(branch, batch)
        .orElseGet(() -> levels.save(new StockLevel(branch, batch)))
        .change(outputQuantity);
    Movement made =
        movements.save(
            new Movement(
                branch,
                output.getName(),
                batch,
                MovementType.TRANSFORM_IN,
                outputQuantity,
                before + outputQuantity,
                number,
                null,
                note));
    if (rounding.signum() != 0
        || (previousCost != null && previousCost.compareTo(batch.getUnitCost()) != 0)) {
      events.publish(
          "stock.cost_averaged",
          meta ->
              new BatchCostAveraged(
                  meta,
                  outputUid,
                  output.getName(),
                  batch.getUid(),
                  batch.getBatchNumber(),
                  previousCost == null ? batch.getUnitCost() : previousCost,
                  unitCost,
                  batch.getUnitCost(),
                  made.getUid(),
                  rounding));
    }
    transformation.output(outputUid, output.getName(), batch, outputQuantity, unitCost);
    return view(transformations.save(transformation));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<TransformationView> list(PageQuery query) {
    UUID branch = ExecutionContext.requireBranch();
    List<Transformation> upTo =
        transformations.findByBranchUidOrderByIdDesc(
            branch, PageRequest.of(0, query.offset() + query.limit() + 1));
    List<Transformation> rows = upTo.subList(Math.min(query.offset(), upTo.size()), upTo.size());
    return Page.offset(rows, query, () -> transformations.countByBranchUid(branch))
        .map(TransformationsImpl::view);
  }

  /**
   * FRD-2: a transformation follows its recipe exactly. A break bulk opens whole packs of the pack
   * the output comes in into their units; a repack fills own packs with exactly the units its pack
   * link says; a compounded product takes exactly its formula, scaled by the quantity made (a
   * fraction rounded up). Inputs left out are taken from the recipe. Refuses 422 no_recipe,
   * recipe_mismatch and inputs_below_formula.
   */
  private List<InputCommand> followRecipe(
      TransformationType type, List<InputCommand> given, ProductRecord output, int made) {
    Map<UUID, Integer> required = new LinkedHashMap<>();
    Map<UUID, String> names = new HashMap<>();
    UUID outputUid = output.getProductUid();
    switch (type) {
      case BREAK_BULK -> {
        Recipes.PackLink link =
            recipes
                .packOf(outputUid)
                .orElseThrow(
                    () ->
                        Refusal.invalid(
                            "no_recipe",
                            output.getName() + " is not linked to a pack it is broken from.",
                            "output.productUid"));
        if (made % link.unitsPerPack() != 0) {
          throw Refusal.invalid(
              "recipe_mismatch",
              "A pack opens into "
                  + link.unitsPerPack()
                  + " of "
                  + output.getName()
                  + "; make a whole number of packs' worth.",
              "output.quantity");
        }
        required.put(link.packProductUid(), made / link.unitsPerPack());
      }
      case REPACK -> {
        List<Recipes.PackLink> links = recipes.linksOfPack(outputUid);
        if (links.isEmpty()) {
          throw Refusal.invalid(
              "no_recipe",
              output.getName() + " has no pack link saying what fills it.",
              "output.productUid");
        }
        Recipes.PackLink link = links.getFirst();
        if (links.size() > 1 && given != null) {
          link =
              links.stream()
                  .filter(
                      l ->
                          given.stream()
                              .anyMatch(
                                  in -> in != null && l.unitProductUid().equals(in.productUid())))
                  .findFirst()
                  .orElse(link);
        }
        required.put(link.unitProductUid(), link.unitsPerPack() * made);
      }
      case COMPOUND -> {
        List<Recipes.Ingredient> formula = recipes.formula(outputUid);
        if (formula.isEmpty()) {
          throw Refusal.invalid(
              "no_recipe", output.getName() + " has no formula.", "output.productUid");
        }
        for (Recipes.Ingredient item : formula) {
          required.merge(
              item.productUid(),
              item.quantity()
                  .multiply(BigDecimal.valueOf(made))
                  .setScale(0, RoundingMode.CEILING)
                  .intValueExact(),
              Integer::sum);
          names.put(item.productUid(), item.name());
        }
      }
    }
    if (given == null || given.isEmpty()) {
      List<InputCommand> inputs = new ArrayList<>();
      required.forEach((product, quantity) -> inputs.add(new InputCommand(product, quantity)));
      return inputs;
    }
    Map<UUID, Integer> have = new LinkedHashMap<>();
    for (int i = 0; i < given.size(); i++) {
      InputCommand in = Checks.required(given.get(i), "inputs[" + i + "]");
      UUID uid = Checks.required(in.productUid(), "inputs[" + i + "].productUid");
      int quantity =
          Checks.positive(in.quantity() == null ? 0 : in.quantity(), "inputs[" + i + "].quantity");
      if (!required.containsKey(uid)) {
        throw Refusal.invalid(
            "recipe_mismatch",
            "That product is not in the recipe of " + output.getName() + ".",
            "inputs[" + i + "].productUid");
      }
      have.merge(uid, quantity, Integer::sum);
    }
    for (Map.Entry<UUID, Integer> need : required.entrySet()) {
      int quantity = have.getOrDefault(need.getKey(), 0);
      String name = names.getOrDefault(need.getKey(), nameOf(need.getKey()));
      if (quantity < need.getValue()) {
        throw Refusal.invalid(
            "inputs_below_formula",
            "Making "
                + made
                + " needs "
                + need.getValue()
                + " of "
                + name
                + " by its recipe; "
                + quantity
                + " given.",
            "inputs");
      }
      if (quantity > need.getValue()) {
        throw Refusal.invalid(
            "recipe_mismatch",
            "Making "
                + made
                + " takes exactly "
                + need.getValue()
                + " of "
                + name
                + " by its recipe; "
                + quantity
                + " given.",
            "inputs");
      }
    }
    return given;
  }

  /** FRD-2: no prescription or controlled medicine becomes a product of a lower class. */
  private void refuseDowngrade(List<InputCommand> inputs, ProductRecord output) {
    int outputRank = controls.rank(output.getProductUid());
    for (int i = 0; i < inputs.size(); i++) {
      UUID input = inputs.get(i).productUid();
      if (controls.rank(input) > outputRank) {
        throw Refusal.invalid(
            "schedule_downgrade",
            nameOf(input)
                + " is a "
                + (controls.rank(input) == 2 ? "controlled" : "prescription")
                + " medicine; it can only be made into a product of the same or a stricter"
                + " class, and "
                + output.getName()
                + " is not.",
            "output.productUid");
      }
    }
  }

  private String nameOf(UUID productUid) {
    return products
        .findByProductUid(productUid)
        .map(ProductRecord::getName)
        .or(() -> catalogue.product(productUid).map(ProductSnapshot::name))
        .orElse("an input");
  }

  private static String prefix(TransformationType type) {
    return switch (type) {
      case BREAK_BULK -> "BB";
      case REPACK -> "RP";
      case COMPOUND -> "CP";
    };
  }

  static TransformationView view(Transformation t) {
    List<InputView> inputs = new ArrayList<>();
    for (TransformationInput in : t.getInputs()) {
      inputs.add(
          new InputView(
              in.getProductUid(),
              in.getProductName(),
              in.getBatchNumber(),
              in.getExpiryDate(),
              in.getQuantity(),
              in.getUnitCost()));
    }
    return new TransformationView(
        t.getUid(),
        t.getNumber(),
        t.getType(),
        inputs,
        t.getOutputProductUid(),
        t.getOutputProductName(),
        t.getOutputBatchNumber(),
        t.getOutputExpiryDate(),
        t.getOutputQuantity(),
        t.getOutputUnitCost(),
        t.getNote(),
        t.getCreatedAt(),
        t.getCreatedBy());
  }

  private ProductRecord product(UUID productUid, String field) {
    products.lockSnapshot(productUid);
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
                                        "product_not_found", "No such product.", field)))));
  }

  private LocalDate today() {
    return calendar.today();
  }
}
