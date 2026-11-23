-- How a return's refund went back, by the way the sale was paid: the cash share in cash, a mobile
-- money, card, bank or cheque share by that method, a credit or account-credit share to the
-- customer's account, a voucher share back onto the voucher it was spent from, and the insurer's
-- share off the claim. Returns recorded before this have no rows: their refund_method took it all.
create table sales.sale_return_refunds (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  return_id      bigint not null references sales.sale_returns (id),
  method         text not null
                   check (method in ('CASH', 'CARD', 'MOBILE_MONEY', 'INSURANCE', 'CREDIT',
                                     'ACCOUNT_CREDIT', 'BANK_TRANSFER', 'CHEQUE', 'VOUCHER')),
  amount         numeric(14,2) not null check (amount > 0),
  reference      text,
  created_at     timestamptz not null,
  created_by     text not null,
  created_by_uid uuid,
  updated_at     timestamptz,
  updated_by     text,
  updated_by_uid uuid,
  version        bigint not null default 0
);
create index sale_return_refunds_by_return_id on sales.sale_return_refunds (return_id);
alter table sales.sale_return_refunds enable row level security;
alter table sales.sale_return_refunds force row level security;
create policy tenant_isolation on sales.sale_return_refunds
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.sale_return_refunds to ${application_role};
