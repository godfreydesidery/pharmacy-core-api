package tz.co.pharmacy.modules.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The SMS cap counts the pharmacy's calendar month, in its own time zone (INT-5). */
class SmsCapWindowTest {

  private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam"); // UTC+3

  @Test
  @DisplayName("late on 30 November UTC is already December in Dar es Salaam")
  void monthInThePharmacysZone() {
    SmsCap.Window window = SmsCap.Window.of(Instant.parse("2026-11-30T22:30:00Z"), DAR);
    assertThat(window.month()).isEqualTo(YearMonth.of(2026, 12));
    assertThat(window.from()).isEqualTo(Instant.parse("2026-11-30T21:00:00Z"));
    assertThat(window.to()).isEqualTo(Instant.parse("2026-12-31T21:00:00Z"));
  }

  @Test
  @DisplayName("in UTC the same moment is still November")
  void monthInUtc() {
    SmsCap.Window window =
        SmsCap.Window.of(Instant.parse("2026-11-30T22:30:00Z"), ZoneId.of("UTC"));
    assertThat(window.month()).isEqualTo(YearMonth.of(2026, 11));
    assertThat(window.from()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    assertThat(window.to()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
  }
}
