package tz.co.pharmacy.modules.insurance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.insurance.ports.inbound.CopayType;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageLine;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageQuote;

class CoverageCalculatorTest {

  static final UUID MEMBER = UUID.randomUUID();
  static final UUID AMOXICILLIN = UUID.randomUUID();
  static final UUID VITAMIN_C = UUID.randomUUID();
  static final UUID WHEELCHAIR = UUID.randomUUID();

  static List<CoverageLine> basket() {
    return List.of(
        new CoverageLine(AMOXICILLIN, "MEDICINE", new BigDecimal("8000.00")),
        new CoverageLine(VITAMIN_C, "SUPPLEMENT", new BigDecimal("2000.00")),
        new CoverageLine(WHEELCHAIR, "MEDICAL_EQUIPMENT", new BigDecimal("350000.00")));
  }

  @Test
  @DisplayName("scenario 4: 10% co-pay on covered items; excluded types are not covered")
  void percentCopayAndExclusions() {
    CoverageQuote quote =
        CoverageCalculator.quote(
            MEMBER,
            CopayType.PERCENT,
            new BigDecimal("10"),
            null,
            BigDecimal.ZERO,
            Set.of("MEDICAL_EQUIPMENT"),
            Set.of(),
            basket());

    assertThat(quote.covered()).isEqualByComparingTo("9000.00"); // (8000 + 2000) x 90%
    assertThat(quote.copay()).isEqualByComparingTo("1000.00");
    assertThat(quote.notCovered()).isEqualByComparingTo("350000.00");
    assertThat(quote.patientPays()).isEqualByComparingTo("351000.00");
    assertThat(quote.lines().get(2).excluded()).isTrue();
    assertThat(quote.notes()).anyMatch(note -> note.contains("not covered"));
  }

  @Test
  @DisplayName("a fixed co-pay per claim is spread over the covered lines and adds up exactly")
  void fixedCopay() {
    CoverageQuote quote =
        CoverageCalculator.quote(
            MEMBER,
            CopayType.FIXED,
            new BigDecimal("1000.00"),
            null,
            BigDecimal.ZERO,
            Set.of(),
            Set.of(WHEELCHAIR),
            basket());

    assertThat(quote.copay()).isEqualByComparingTo("1000.00");
    assertThat(quote.covered()).isEqualByComparingTo("9000.00");
    assertThat(quote.lines().get(0).copay()).isEqualByComparingTo("800.00");
    assertThat(quote.lines().get(1).copay()).isEqualByComparingTo("200.00");
    assertThat(quote.lines().get(2).copay()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("the annual limit caps the insurer's share; the excess moves to the patient")
  void annualLimit() {
    CoverageQuote quote =
        CoverageCalculator.quote(
            MEMBER,
            CopayType.NONE,
            null,
            new BigDecimal("500000.00"),
            new BigDecimal("495000.00"),
            Set.of(),
            Set.of(),
            basket().subList(0, 2));

    assertThat(quote.covered()).isEqualByComparingTo("5000.00");
    assertThat(quote.notCovered()).isEqualByComparingTo("5000.00");
    assertThat(quote.covered().add(quote.copay()).add(quote.notCovered()))
        .isEqualByComparingTo("10000.00");
    assertThat(quote.notes()).anyMatch(note -> note.contains("annual limit"));
  }

  @Test
  @DisplayName("every line splits exactly: covered + co-pay + not covered = line total")
  void linesAddUp() {
    CoverageQuote quote =
        CoverageCalculator.quote(
            MEMBER,
            CopayType.PERCENT,
            new BigDecimal("12.5"),
            new BigDecimal("300000"),
            new BigDecimal("1000"),
            Set.of(),
            Set.of(),
            basket());
    quote
        .lines()
        .forEach(
            line ->
                assertThat(line.covered().add(line.copay()).add(line.notCovered()))
                    .isEqualByComparingTo(line.lineTotal()));
    assertThat(quote.covered()).isEqualByComparingTo("299000.00");
  }
}
