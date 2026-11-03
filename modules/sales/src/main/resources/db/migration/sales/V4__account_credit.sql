-- ACCOUNT_CREDIT: a customer's deposit, overpayment or credit note spent at the till, and a refund
-- kept on the customer's account. The inline checks of V1 are replaced by named ones.

alter table sales.sale_payments drop constraint if exists sale_payments_method_check;
alter table sales.sale_payments
  add constraint sale_payments_method_check
  check (method in ('CASH', 'CARD', 'MOBILE_MONEY', 'INSURANCE', 'CREDIT', 'ACCOUNT_CREDIT'));

alter table sales.sale_returns drop constraint if exists sale_returns_refund_method_check;
alter table sales.sale_returns
  add constraint sale_returns_refund_method_check
  check (refund_method in ('CASH', 'CARD', 'MOBILE_MONEY', 'INSURANCE', 'CREDIT', 'ACCOUNT_CREDIT'));
