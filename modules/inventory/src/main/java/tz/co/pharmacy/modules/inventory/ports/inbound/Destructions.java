package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * The destruction register of the current branch: expired or damaged stock destroyed before a
 * witness. Each line is written off as an adjustment (EXPIRED_WRITE_OFF or DAMAGE).
 */
public interface Destructions {

  /**
   * Records the destruction (DST-000001) and writes off its lines; 422 insufficient_stock,
   * duplicate_batch, batch_not_found, invalid reason (EXPIRED or DAMAGED).
   */
  DestructionView record(DestructionCommand command);

  /** The current branch's destructions, newest first (100 at most). */
  Page<DestructionView> list(PageQuery query);

  /** 404 destruction_not_found. */
  DestructionView get(UUID uid);

  record DestructionCommand(
      LocalDate destroyedOn,
      String method,
      String witnessName,
      String witnessTitle,
      String certificateNumber,
      String note,
      List<LineCommand> lines) {}

  record LineCommand(UUID batchUid, Integer quantity, String reason) {}

  record DestructionView(
      UUID uid,
      String number,
      UUID branchUid,
      String branchName,
      LocalDate destroyedOn,
      String method,
      String witnessName,
      String witnessTitle,
      String certificateNumber,
      String note,
      BigDecimal totalCost,
      Instant createdAt,
      String createdBy,
      String createdByName,
      List<LineView> lines) {}

  record LineView(
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      String reason,
      int quantity,
      BigDecimal unitCost,
      BigDecimal value) {}
}
