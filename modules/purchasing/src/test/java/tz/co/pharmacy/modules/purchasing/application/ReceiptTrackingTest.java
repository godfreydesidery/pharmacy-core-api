package tz.co.pharmacy.modules.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReceiptLineCommand;
import tz.co.pharmacy.platform.kernel.Refusal;

class ReceiptTrackingTest {

  @Test
  @DisplayName("a medicine needs its batch and expiry; gloves only a batch; crutches nothing more")
  void requirementsByTracking() {
    assertThatThrownBy(() -> ReceiptTracking.check("BATCH_EXPIRY", line("B1", null, 5, null), 0))
        .isInstanceOf(Refusal.class)
        .extracting(refusal -> ((Refusal) refusal).field())
        .isEqualTo("lines[0].expiryDate");
    ReceiptTracking.check("BATCH", line("GLV-1", null, 40, null), 0);
    ReceiptTracking.check("QUANTITY", line(null, null, 10, null), 0);
    assertThat(ReceiptTracking.batchNumber(line(null, null, 10, null))).isEqualTo("-");
  }

  @Test
  @DisplayName("serial units need one serial number each, listed once")
  void serials() {
    ReceiptTracking.check("SERIAL", line(null, null, 2, List.of("WCH-1", "WCH-2")), 0);
    assertThatThrownBy(() -> ReceiptTracking.check("SERIAL", line(null, null, 3, List.of("W")), 1))
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("serials_mismatch");
    assertThatThrownBy(
            () -> ReceiptTracking.check("SERIAL", line(null, null, 2, List.of("W", "W")), 1))
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("duplicate_serial");
    assertThatThrownBy(
            () ->
                ReceiptTracking.check(
                    "BATCH_EXPIRY", line("B1", LocalDate.now(), 1, List.of("S1")), 0))
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("serials_not_tracked");
  }

  private static ReceiptLineCommand line(
      String batch, LocalDate expiry, int quantity, List<String> serials) {
    return new ReceiptLineCommand(
        UUID.randomUUID(), batch, expiry, quantity, BigDecimal.TEN, serials);
  }
}
