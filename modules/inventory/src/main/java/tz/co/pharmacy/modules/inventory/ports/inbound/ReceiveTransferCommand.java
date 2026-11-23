package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * What arrived of a transfer (FRD-15). Each line names a transfer line by its batch (or by its
 * product when the product came in one batch) and the units received; a line not named arrived in
 * full. Units short are a loss of the sending branch: the {@code note} says why, and it is required
 * when anything is short.
 */
public record ReceiveTransferCommand(String note, List<ReceivedLine> lines) {

  /**
   * {@code serialNumbers}: for a serial-numbered product, the units that arrived (by default the
   * first {@code receivedQuantity} dispatched).
   */
  public record ReceivedLine(
      UUID batchUid, UUID productUid, Integer receivedQuantity, List<String> serialNumbers) {}
}
