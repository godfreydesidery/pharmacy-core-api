package tz.co.pharmacy.modules.integrations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tz.co.pharmacy.modules.integrations.domain.PaymentMethodType;
import tz.co.pharmacy.modules.integrations.persistence.PaymentIntentRepository;
import tz.co.pharmacy.modules.integrations.persistence.PaymentMethodTypeRepository;
import tz.co.pharmacy.modules.integrations.persistence.TenantPaymentMethodRepository;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments;
import tz.co.pharmacy.platform.kernel.Refusal;

/** The SIMULATOR payment adapter exists only under the sim, local and test profiles (INT-15). */
class PaymentAdaptersProfileTest {

  private static PaymentAdapters under(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return new PaymentAdapters("confirm", environment);
  }

  @Test
  @DisplayName("registered under sim, local or test; absent in production profiles")
  void byProfile() {
    assertThat(under("sim").of("SIMULATOR")).isNotNull();
    assertThat(under("local").of("SIMULATOR")).isNotNull();
    assertThat(under("local", "sim").of("SIMULATOR")).isNotNull();
    assertThat(under("test").of("SIMULATOR")).isNotNull();
    assertThat(under().of("SIMULATOR")).isNull();
    assertThat(under("prod").of("SIMULATOR")).isNull();
    assertThat(under("prod").usable("SIMULATOR")).isFalse();
    assertThat(under("prod").usable("MPESA")).isTrue();
    assertThat(under("prod").usable("MANUAL")).isTrue();
  }

  @Test
  @DisplayName("without it, the simulator cannot be registered, chosen, offered or pushed")
  void cannotBeChosen() {
    PaymentMethodTypeRepository types = mock(PaymentMethodTypeRepository.class);
    TenantPaymentMethodRepository tenantMethods = mock(TenantPaymentMethodRepository.class);
    PaymentIntentRepository intents = mock(PaymentIntentRepository.class);
    PaymentMethodType simulator = new PaymentMethodType("SIMULATOR");
    simulator.change("Test push", "MOBILE_MONEY", "SIMULATOR", true, true, 90);
    PaymentMethodType cash = new PaymentMethodType("CASH");
    cash.change("Cash", "CASH", "NONE", false, true, 0);
    when(types.findByCode("SIMULATOR")).thenReturn(Optional.of(simulator));
    when(types.findByCode("CASH")).thenReturn(Optional.of(cash));
    when(types.findAllByOrderBySortOrderAscCodeAsc()).thenReturn(List.of(cash, simulator));
    when(tenantMethods.findAllByOrderByIdAsc()).thenReturn(List.of());
    when(types.save(any())).thenAnswer(call -> call.getArgument(0));
    PaymentsImpl payments =
        new PaymentsImpl(
            types,
            tenantMethods,
            intents,
            under("prod"),
            Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC));

    assertThat(reason(() -> payments.saveMethodType(command("SIMULATOR"))))
        .isEqualTo("adapter_not_available");
    assertThat(
            reason(
                () ->
                    payments.saveTenantMethod(
                        new Payments.TenantMethodCommand("SIMULATOR", null, null, true))))
        .isEqualTo("adapter_not_available");
    assertThat(
            reason(
                () ->
                    payments.requestPayment(
                        new Payments.RequestPaymentCommand(
                            "SIMULATOR", new BigDecimal("1000"), "TZS", "+255754000111"))))
        .isEqualTo("adapter_not_available");
    assertThat(payments.tenantMethods())
        .extracting(Payments.TenantMethodView::code)
        .containsExactly("CASH");
    assertThat(payments.verify("SIMULATOR", new BigDecimal("1000"), "SIM-1", "INV-1").approved())
        .isFalse();
    // A real adapter's registry entry still saves.
    assertThat(payments.saveMethodType(command("CASH")).code()).isEqualTo("CASH");
  }

  private static Payments.MethodTypeCommand command(String code) {
    return "CASH".equals(code)
        ? new Payments.MethodTypeCommand(code, "Cash", "CASH", "NONE", false, true, 0)
        : new Payments.MethodTypeCommand(
            code, "Test push", "MOBILE_MONEY", "SIMULATOR", true, true, 90);
  }

  private static String reason(Runnable call) {
    Refusal refusal = catchThrowableOfType(Refusal.class, call::run);
    assertThat(refusal).as("refused").isNotNull();
    return refusal.reason();
  }
}
