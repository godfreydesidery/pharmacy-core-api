package tz.co.pharmacy.modules.sales.ports.inbound;

/**
 * How a sale is paid. CREDIT, INSURANCE and ACCOUNT_CREDIT (spending a customer's deposit or
 * overpayment) need a customer.
 */
public enum PaymentMethod {
  CASH,
  CARD,
  MOBILE_MONEY,
  INSURANCE,
  CREDIT,
  ACCOUNT_CREDIT
}
