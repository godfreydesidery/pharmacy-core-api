package tz.co.pharmacy.modules.integrations.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.integrations.application.PaymentAdapters.Outcome;
import tz.co.pharmacy.modules.integrations.application.PaymentAdapters.PaymentAdapter;
import tz.co.pharmacy.modules.integrations.domain.PaymentIntent;
import tz.co.pharmacy.modules.integrations.domain.PaymentMethodType;
import tz.co.pharmacy.modules.integrations.domain.TenantPaymentMethod;
import tz.co.pharmacy.modules.integrations.persistence.PaymentIntentRepository;
import tz.co.pharmacy.modules.integrations.persistence.PaymentMethodTypeRepository;
import tz.co.pharmacy.modules.integrations.persistence.TenantPaymentMethodRepository;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

@Service
@RequiredArgsConstructor
class PaymentsImpl implements Payments {

  private static final Set<String> KINDS =
      Set.of("CASH", "MOBILE_MONEY", "BANK_TRANSFER", "CARD", "CHEQUE", "VOUCHER");
  private static final Set<String> ADAPTERS =
      Set.of("NONE", "MANUAL", "SIMULATOR", "MPESA", "MIXX", "AIRTEL", "SELCOM", "AZAMPAY");
  private static final Duration REQUEST_LIFETIME = Duration.ofMinutes(5);

  private final PaymentMethodTypeRepository types;
  private final TenantPaymentMethodRepository tenantMethods;
  private final PaymentIntentRepository intents;
  private final PaymentAdapters adapters;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public List<MethodTypeView> methodTypes() {
    return types.findAllByOrderBySortOrderAscCodeAsc().stream().map(this::view).toList();
  }

  @Override
  @Transactional
  public MethodTypeView saveMethodType(MethodTypeCommand c) {
    String code = Checks.text(c.code(), "code", 40).trim().toUpperCase();
    if (!code.matches("[A-Z0-9_]{2,40}")) {
      throw Refusal.invalid("invalid_code", "code is letters, digits and _ only.", "code");
    }
    String kind = Checks.text(c.kind(), "kind", 20).toUpperCase();
    if (!KINDS.contains(kind)) {
      throw Refusal.invalid("invalid_kind", "Unknown kind " + kind + ".", "kind");
    }
    String adapter =
        c.adapter() == null || c.adapter().isBlank() ? "MANUAL" : c.adapter().toUpperCase();
    if (!ADAPTERS.contains(adapter)) {
      throw Refusal.invalid("invalid_adapter", "Unknown adapter " + adapter + ".", "adapter");
    }
    PaymentMethodType type = types.findByCode(code).orElseGet(() -> new PaymentMethodType(code));
    type.change(
        Checks.text(c.name(), "name", 80),
        kind,
        adapter,
        Boolean.TRUE.equals(c.requiresReference()),
        c.enabled() == null || c.enabled(),
        c.sortOrder() == null ? 100 : c.sortOrder());
    return view(types.save(type));
  }

  @Override
  @Transactional(readOnly = true)
  public List<TenantMethodView> tenantMethods() {
    Map<String, TenantPaymentMethod> chosen = chosen();
    List<TenantMethodView> out = new ArrayList<>();
    for (PaymentMethodType type : types.findAllByOrderBySortOrderAscCodeAsc()) {
      if (type.isEnabled()) {
        out.add(view(type, chosen.get(type.getCode()), chosen.isEmpty()));
      }
    }
    return out;
  }

  @Override
  @Transactional
  public TenantMethodView saveTenantMethod(TenantMethodCommand c) {
    String code = Checks.text(c.methodCode(), "methodCode", 40).toUpperCase();
    PaymentMethodType type =
        types
            .findByCode(code)
            .filter(PaymentMethodType::isEnabled)
            .orElseThrow(
                () ->
                    Refusal.invalid(
                        "method_not_found",
                        "No such payment method on the platform.",
                        "methodCode"));
    if (tenantMethods.findAllByOrderByIdAsc().isEmpty()) {
      // The first choice turns "everything accepted" into an explicit list: keep the rest on.
      for (PaymentMethodType other : types.findAllByOrderBySortOrderAscCodeAsc()) {
        if (other.isEnabled() && !other.getCode().equals(code)) {
          TenantPaymentMethod on = new TenantPaymentMethod(other.getCode());
          on.change(null, null, !"SIMULATOR".equals(other.getAdapter()));
          tenantMethods.save(on);
        }
      }
    }
    TenantPaymentMethod method =
        tenantMethods.findByMethodCode(code).orElseGet(() -> new TenantPaymentMethod(code));
    method.change(
        Checks.optionalText(c.label(), "label", 80),
        Checks.optionalText(c.accountReference(), "accountReference", 120),
        c.enabled() == null || c.enabled());
    tenantMethods.save(method);
    return view(type, method, false);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TenantMethodView> tillMethods() {
    return tenantMethods().stream().filter(TenantMethodView::enabled).toList();
  }

  @Override
  @Transactional
  public IntentView requestPayment(RequestPaymentCommand c) {
    String code = Checks.text(c.methodCode(), "methodCode", 40).toUpperCase();
    TenantMethodView method =
        tillMethods().stream()
            .filter(m -> m.code().equals(code))
            .findFirst()
            .orElseThrow(
                () ->
                    Refusal.invalid(
                        "method_not_accepted",
                        "This pharmacy does not accept " + code + ".",
                        "methodCode"));
    PaymentAdapter adapter = adapters.of(method.adapter());
    if (adapter == null || !adapter.available()) {
      throw Refusal.invalid(
          "adapter_unavailable",
          method.name()
              + " cannot send a request to the phone yet; take the payment on the customer's"
              + " phone and enter its confirmation code.",
          "methodCode");
    }
    BigDecimal amount = Checks.required(c.amount(), "amount");
    if (amount.signum() <= 0) {
      throw Refusal.invalid("invalid_amount", "amount must be more than zero.", "amount");
    }
    String phone = Checks.text(c.phone(), "phone", 20);
    if (!phone.matches("\\+?[0-9]{9,15}")) {
      throw Refusal.invalid("invalid_phone", "Enter the customer's mobile number.", "phone");
    }
    String currency = Checks.text(c.currency(), "currency", 3).toUpperCase();
    PaymentIntent intent =
        new PaymentIntent(
            ExecutionContext.current().map(f -> f.branchUid()).orElse(null),
            code,
            Amounts.money(amount),
            currency,
            phone,
            clock.instant().plus(REQUEST_LIFETIME));
    Outcome outcome = adapter.push(phone, intent.getAmount(), currency, method.accountReference());
    intent.providerReference(outcome.providerReference());
    if ("FAILED".equals(outcome.status())) {
      intent.failed(outcome.failureReason());
    }
    return view(intents.save(intent));
  }

  @Override
  @Transactional
  public IntentView paymentRequest(UUID uid) {
    UUID branch = ExecutionContext.branchUid().orElse(null);
    PaymentIntent intent =
        intents
            .lockByUid(uid)
            // Another branch's request is not found, like its sales.
            .filter(found -> branch == null || branch.equals(found.getBranchUid()))
            .orElseThrow(() -> Refusal.notFound("payment_request_not_found", "No such request."));
    refresh(intent);
    return view(intent);
  }

  @Override
  @Transactional
  public Verification verify(
      String methodCode, BigDecimal amount, String reference, String saleNumber) {
    if (methodCode == null || methodCode.isBlank()) {
      return new Verification(true, reference, null);
    }
    String code = methodCode.trim().toUpperCase();
    Optional<TenantMethodView> method =
        tillMethods().stream().filter(m -> m.code().equals(code)).findFirst();
    if (method.isEmpty()) {
      return new Verification(false, null, "This pharmacy does not accept " + code + ".");
    }
    Optional<PaymentIntent> intent = intentOf(reference);
    if (intent.isPresent()) {
      PaymentIntent request = intent.get();
      refresh(request);
      if (!"CONFIRMED".equals(request.getStatus())) {
        return new Verification(
            false, null, "The " + method.get().name() + " request is " + request.getStatus() + ".");
      }
      if (Amounts.money(amount).compareTo(request.getAmount()) != 0) {
        return new Verification(
            false,
            null,
            "The confirmed request was for " + request.getAmount() + ", not " + amount + ".");
      }
      request.usedBy(saleNumber);
      return new Verification(true, request.getProviderReference(), null);
    }
    if (method.get().requiresReference() && (reference == null || reference.isBlank())) {
      return new Verification(
          false, null, "Enter the " + method.get().name() + " reference or confirmation code.");
    }
    return new Verification(true, reference, null);
  }

  private Optional<PaymentIntent> intentOf(String reference) {
    if (reference == null) {
      return Optional.empty();
    }
    try {
      return intents.lockByUid(UUID.fromString(reference.trim()));
    } catch (IllegalArgumentException notUuid) {
      return Optional.empty();
    }
  }

  private void refresh(PaymentIntent intent) {
    if (!"PENDING".equals(intent.getStatus())) {
      return;
    }
    if (clock.instant().isAfter(intent.getExpiresAt())) {
      intent.expired();
      return;
    }
    String adapterCode =
        types.findByCode(intent.getMethodCode()).map(PaymentMethodType::getAdapter).orElse("NONE");
    PaymentAdapter adapter = adapters.of(adapterCode);
    if (adapter == null || !adapter.available()) {
      return;
    }
    Outcome outcome = adapter.status(intent.getProviderReference());
    if ("CONFIRMED".equals(outcome.status())) {
      intent.confirmed(outcome.providerReference());
    } else if ("FAILED".equals(outcome.status())) {
      intent.failed(outcome.failureReason());
    }
  }

  private Map<String, TenantPaymentMethod> chosen() {
    return tenantMethods.findAllByOrderByIdAsc().stream()
        .collect(Collectors.toMap(TenantPaymentMethod::getMethodCode, m -> m, (a, b) -> a));
  }

  private MethodTypeView view(PaymentMethodType t) {
    return new MethodTypeView(
        t.getUid(),
        t.getCode(),
        t.getName(),
        t.getKind(),
        t.getAdapter(),
        t.isRequiresReference(),
        t.isEnabled(),
        t.getSortOrder(),
        adapters.pushAvailable(t.getAdapter()));
  }

  /** With no choices recorded yet, a pharmacy accepts every enabled method except the simulator. */
  private TenantMethodView view(PaymentMethodType t, TenantPaymentMethod chosen, boolean defaults) {
    boolean enabled =
        chosen != null ? chosen.isEnabled() : defaults && !"SIMULATOR".equals(t.getAdapter());
    return new TenantMethodView(
        t.getCode(),
        t.getName(),
        chosen == null || chosen.getLabel() == null ? t.getName() : chosen.getLabel(),
        t.getKind(),
        t.getAdapter(),
        t.isRequiresReference(),
        adapters.pushAvailable(t.getAdapter()),
        chosen == null ? null : chosen.getAccountReference(),
        enabled);
  }

  private static IntentView view(PaymentIntent i) {
    return new IntentView(
        i.getUid(),
        i.getMethodCode(),
        i.getAmount(),
        i.getCurrency(),
        i.getPhone(),
        i.getStatus(),
        i.getProviderReference(),
        i.getFailureReason(),
        i.getExpiresAt());
  }
}
