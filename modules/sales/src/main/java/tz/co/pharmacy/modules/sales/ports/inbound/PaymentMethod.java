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
  ACCOUNT_CREDIT,
  /** A bank transfer or deposit, by reference; posted to the bank account. */
  BANK_TRANSFER,
  /** A cheque, by number; posted to the bank account (cleared later). */
  CHEQUE,
  /** A prepaid voucher or gift card, by its code; spends its balance. */
  VOUCHER
}
