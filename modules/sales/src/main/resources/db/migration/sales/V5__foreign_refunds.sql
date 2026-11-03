-- A refund paid back in the foreign currency the customer tendered. refund_total stays the base
-- value booked at the sale; the cash that leaves is refund_foreign_amount at today's rate, and the
-- difference (base paid out − booked) is the realised exchange gain or loss.

alter table sales.sale_returns
  add column refund_currency text,
  add column refund_foreign_amount numeric(18, 4),
  add column sale_exchange_rate numeric(18, 8),
  add column refund_exchange_rate numeric(18, 8),
  add column fx_difference numeric(14, 2) not null default 0;

alter table sales.sale_returns
  add constraint sale_returns_foreign_refund_check
  check (
    (refund_currency is null and refund_foreign_amount is null
      and sale_exchange_rate is null and refund_exchange_rate is null and fx_difference = 0)
    or (refund_currency ~ '^[A-Z]{3}$' and refund_foreign_amount > 0
      and sale_exchange_rate > 0 and refund_exchange_rate > 0));
