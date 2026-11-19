package tz.co.pharmacy.modules.integrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.integrations.application.PaymentAdapters.Outcome;
import tz.co.pharmacy.modules.integrations.application.PaymentAdapters.PaymentAdapter;

class PaymentSimulatorTest {

  static final BigDecimal AMOUNT = new BigDecimal("12000.00");

  static Outcome settle(PaymentAdapter adapter, String phone) {
    Outcome pushed = adapter.push(phone, AMOUNT, "TZS", "ACC");
    assertThat(pushed.status()).isEqualTo("PENDING");
    return adapter.status(pushed.providerReference());
  }

  @Test
  @DisplayName("by default the simulator confirms every request")
  void confirmsByDefault() {
    PaymentAdapter adapter = new PaymentAdapters("confirm").of("SIMULATOR");

    for (String phone : new String[] {"255712000001", "255712000002", "255712000003"}) {
      assertThat(settle(adapter, phone).status()).isEqualTo("CONFIRMED");
    }
    assertThat(adapter.push("255712345678", AMOUNT, "TZS", "ACC").providerReference())
        .matches("SIM-[0-9A-F]{8}");
  }

  @Test
  @DisplayName("by phone, the last two digits choose the provider's answer")
  void answersByPhone() {
    PaymentAdapter adapter = new PaymentAdapters("by-phone").of("SIMULATOR");

    assertThat(settle(adapter, "+255 712 345 678").status()).isEqualTo("CONFIRMED");
    Outcome declined = settle(adapter, "255712000001");
    assertThat(declined.status()).isEqualTo("FAILED");
    assertThat(declined.failureReason()).contains("declined");
    Outcome broke = settle(adapter, "255712000002");
    assertThat(broke.status()).isEqualTo("FAILED");
    assertThat(broke.failureReason()).contains("Insufficient");
    assertThat(settle(adapter, "255712000003").status()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("the provider adapters stay unavailable until they are connected")
  void providersUnavailable() {
    PaymentAdapters adapters = new PaymentAdapters("by-phone");

    for (String code : new String[] {"MPESA", "MIXX", "AIRTEL", "SELCOM", "AZAMPAY"}) {
      assertThat(adapters.pushAvailable(code)).as(code).isFalse();
    }
    assertThat(adapters.pushAvailable("SIMULATOR")).isTrue();
  }
}
