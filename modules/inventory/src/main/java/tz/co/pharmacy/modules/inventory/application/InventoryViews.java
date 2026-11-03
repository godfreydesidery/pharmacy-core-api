package tz.co.pharmacy.modules.inventory.application;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.inventory.domain.Movement;
import tz.co.pharmacy.modules.inventory.domain.Transfer;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementView;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferLineView;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransferView;
import tz.co.pharmacy.modules.inventory.ports.outbound.BranchNames;

/** Maps the module's entities to its views. */
@Component
@RequiredArgsConstructor
class InventoryViews {

  private final BranchNames branchNames;

  MovementView movement(Movement movement) {
    return new MovementView(
        movement.getUid(),
        movement.getProductUid(),
        movement.getProductName(),
        movement.getBatchUid(),
        movement.getBatchNumber(),
        movement.getType(),
        movement.getQuantityChange(),
        movement.getBalanceAfter(),
        movement.getReference(),
        movement.getReasonCode(),
        movement.getNote(),
        movement.getCreatedAt(),
        movement.getCreatedBy(),
        movement.getSerialNumbers());
  }

  TransferView transfer(Transfer transfer) {
    return transfer(transfer, new HashMap<>());
  }

  /** As {@link #transfer(Transfer)}, reusing branch names already looked up for a page. */
  TransferView transfer(Transfer transfer, Map<UUID, String> names) {
    return new TransferView(
        transfer.getUid(),
        transfer.getNumber(),
        transfer.getFromBranchUid(),
        names.computeIfAbsent(transfer.getFromBranchUid(), this::branchName),
        transfer.getToBranchUid(),
        names.computeIfAbsent(transfer.getToBranchUid(), this::branchName),
        transfer.getStatus(),
        transfer.getNote(),
        transfer.getLines().stream()
            .map(
                line ->
                    new TransferLineView(
                        line.getProductUid(),
                        line.getProductName(),
                        line.getBatchUid(),
                        line.getBatchNumber(),
                        line.getExpiryDate(),
                        line.getQuantity(),
                        line.getSerialNumbers()))
            .toList(),
        transfer.getCreatedAt(),
        transfer.getReceivedAt());
  }

  String branchName(UUID branchUid) {
    return branchNames.name(branchUid);
  }
}
