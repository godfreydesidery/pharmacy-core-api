package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Turning stock of some products into stock of another, at the current branch. */
public interface Transformations {

  /**
   * Takes the inputs earliest expiry first and makes the output batch: its expiry is the earliest
   * input expiry (or an earlier one given, never a later one), its unit cost the inputs' cost
   * shared over the output units. Refuses 422 insufficient_stock, serial_not_supported,
   * expiry_after_inputs and same_product.
   */
  TransformationView transform(TransformCommand command);

  Page<TransformationView> list(PageQuery query);

  record InputCommand(UUID productUid, Integer quantity) {}

  record OutputCommand(
      UUID productUid, Integer quantity, String batchNumber, LocalDate expiryDate) {}

  record TransformCommand(
      TransformationType type, List<InputCommand> inputs, OutputCommand output, String note) {}

  record InputView(
      UUID productUid,
      String productName,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitCost) {}

  record TransformationView(
      UUID uid,
      String number,
      TransformationType type,
      List<InputView> inputs,
      UUID outputProductUid,
      String outputProductName,
      String outputBatchNumber,
      LocalDate outputExpiryDate,
      int outputQuantity,
      BigDecimal outputUnitCost,
      String note,
      Instant createdAt,
      String createdBy) {}
}
