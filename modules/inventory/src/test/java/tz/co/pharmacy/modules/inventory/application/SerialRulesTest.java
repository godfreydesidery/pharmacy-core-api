package tz.co.pharmacy.modules.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.inventory.domain.Batch;
import tz.co.pharmacy.platform.kernel.Refusal;

class SerialRulesTest {

  @Test
  @DisplayName("serial numbers are trimmed and blanks dropped")
  void normalises() {
    assertThat(SerialRules.normalise(Arrays.asList(" WCH-0001 ", "", null, "WCH-0002"), "f"))
        .containsExactly("WCH-0001", "WCH-0002");
    assertThat(SerialRules.normalise(null, "f")).isEmpty();
  }

  @Test
  @DisplayName("a serial number listed twice, too long or with a comma is refused")
  void refusesBadSerials() {
    assertThatThrownBy(() -> SerialRules.normalise(List.of("A1", "A1"), "f"))
        .isInstanceOf(Refusal.class)
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("duplicate_serial");
    assertThatThrownBy(() -> SerialRules.normalise(List.of("A,1"), "f"))
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("invalid_serial");
    assertThatThrownBy(() -> SerialRules.normalise(List.of("X".repeat(65)), "f"))
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("invalid_serial");
  }

  @Test
  @DisplayName("one serial number per unit, else serials_mismatch about the field")
  void countMustMatch() {
    SerialRules.requireCount(List.of("A", "B", "C"), 3, "serialNumbers");
    assertThatThrownBy(() -> SerialRules.requireCount(List.of("A"), 3, "lines[0].serialNumbers"))
        .isInstanceOf(Refusal.class)
        .satisfies(
            refusal -> {
              assertThat(((Refusal) refusal).reason()).isEqualTo("serials_mismatch");
              assertThat(((Refusal) refusal).field()).isEqualTo("lines[0].serialNumbers");
            });
  }

  @Test
  @DisplayName("a batch without expiry never expires (it is sold first in, first out)")
  void batchWithoutExpiry() {
    Batch gloves = new Batch(UUID.randomUUID(), "GLV-2026A", null, BigDecimal.TEN);
    Batch amoxicillin =
        new Batch(UUID.randomUUID(), "AMX-1", LocalDate.of(2026, 1, 31), BigDecimal.TEN);
    LocalDate today = LocalDate.of(2026, 11, 3);
    assertThat(gloves.expiredOn(today)).isFalse();
    assertThat(amoxicillin.expiredOn(today)).isTrue();
  }
}
