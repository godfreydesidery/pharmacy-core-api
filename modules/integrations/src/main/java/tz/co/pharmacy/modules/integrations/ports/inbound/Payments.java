package tz.co.pharmacy.modules.integrations.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payment methods and payment requests: the platform registry (root administrator), the methods a
 * pharmacy accepts, pushing a request to a customer's phone, and verifying a tender at checkout.
 */
public interface Payments {

  /** The platform registry, every method. */
  List<MethodTypeView> methodTypes();

  /**
   * Adds or changes a registry entry (root administrator). The SIMULATOR adapter exists only under
   * the sim, local and test profiles: elsewhere 422 {@code adapter_not_available}.
   */
  MethodTypeView saveMethodType(MethodTypeCommand command);

  /** The registry's enabled methods with this pharmacy's choices and details. */
  List<TenantMethodView> tenantMethods();

  /** Enables or disables a method for this pharmacy, with its label and merchant details. */
  TenantMethodView saveTenantMethod(TenantMethodCommand command);

  /** What the till offers: methods enabled on the platform and by this pharmacy. */
  List<TenantMethodView> tillMethods();

  /**
   * Pushes a payment request to the customer's phone through the method's adapter. Refuses 422
   * {@code adapter_unavailable} when the provider is not connected (take its reference instead).
   */
  IntentView requestPayment(RequestPaymentCommand command);

  /** A request's state, asking the provider when still pending. */
  IntentView paymentRequest(UUID uid);

  /**
   * Approves a tender at checkout: a push method needs its confirmed, unused request (the
   * reference) for the same amount, method and branch (422 {@code payment_mismatch} when the
   * request was made for another method or branch), which it then marks used; a method that
   * requires a reference needs one; others pass. Returns the reference to record.
   */
  Verification verify(String methodCode, BigDecimal amount, String reference, String saleNumber);

  record MethodTypeCommand(
      String code,
      String name,
      String kind,
      String adapter,
      Boolean requiresReference,
      Boolean enabled,
      Integer sortOrder) {}

  record MethodTypeView(
      UUID uid,
      String code,
      String name,
      String kind,
      String adapter,
      boolean requiresReference,
      boolean enabled,
      int sortOrder,
      boolean pushAvailable) {}

  record TenantMethodCommand(
      String methodCode, String label, String accountReference, Boolean enabled) {}

  /**
   * {@code kind} is how the tender is booked (CASH, MOBILE_MONEY, BANK_TRANSFER, CARD, CHEQUE,
   * VOUCHER); {@code push} whether a request can be sent to the customer's phone.
   */
  record TenantMethodView(
      String code,
      String name,
      String label,
      String kind,
      String adapter,
      boolean requiresReference,
      boolean push,
      String accountReference,
      boolean enabled) {}

  record RequestPaymentCommand(
      String methodCode, BigDecimal amount, String currency, String phone) {}

  record IntentView(
      UUID uid,
      String methodCode,
      BigDecimal amount,
      String currency,
      String phone,
      String status,
      String providerReference,
      String failureReason,
      Instant expiresAt) {}

  record Verification(boolean approved, String reference, String declineReason) {}
}
