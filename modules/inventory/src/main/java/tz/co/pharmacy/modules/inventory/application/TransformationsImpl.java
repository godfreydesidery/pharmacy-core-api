package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
import tz.co.pharmacy.modules.inventory.persistence.BatchRepository;
import tz.co.pharmacy.modules.inventory.persistence.MovementRepository;
import tz.co.pharmacy.modules.inventory.persistence.ProductRecordRepository;
import tz.co.pharmacy.modules.inventory.persistence.StockLevelRepository;
import tz.co.pharmacy.modules.inventory.persistence.TransformationRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransformationType;
import tz.co.pharmacy.modules.inventory.ports.inbound.Transformations;
import tz.co.pharmacy.modules.inventory.ports.outbound.ProductCatalogue;
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

  @Override
  @Transactional
  public TransformationView transform(TransformCommand command) {
    if (command == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    UUID branch = ExecutionContext.requireBranch();
    TransformationType type = Checks.required(command.type(), "type");
    List<InputCommand> inputs = Checks.notEmpty(command.inputs(), "inputs");
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
    Batch batch =
        batches
            .findByProductUidAndBatchNumber(outputUid, batchNumber)
            .orElseGet(
                () -> batches.save(new Batch(outputUid, batchNumber, batchExpiry, unitCost)));
    if (output.expiryTracked() && !expiry.equals(batch.getExpiryDate())) {
      throw Refusal.invalid(
          "batch_expiry_mismatch",
          "Batch " + batchNumber + " is recorded with expiry " + batch.getExpiryDate() + ".",
          "output.batchNumber");
    }
    int before = (int) levels.quantityAt(branch, outputUid);
    levels
        .lockAt(branch, batch)
        .orElseGet(() -> levels.save(new StockLevel(branch, batch)))
        .change(outputQuantity);
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
    return Page.offset(rows, query).map(TransformationsImpl::view);
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
