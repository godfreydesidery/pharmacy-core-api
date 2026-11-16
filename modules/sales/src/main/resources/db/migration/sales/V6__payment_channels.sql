-- Bank transfers, cheques and vouchers as tenders, and the registered method (channel) a payment
-- went through, for reconciliation. Vouchers: prepaid value sold for cash and spent at the till.

alter table sales.sale_payments drop constraint if exists sale_payments_method_check;
alter table sales.sale_payments
  add constraint sale_payments_method_check
  check (method in ('CASH', 'CARD', 'MOBILE_MONEY', 'INSURANCE', 'CREDIT', 'ACCOUNT_CREDIT',
                    'BANK_TRANSFER', 'CHEQUE', 'VOUCHER'));
alter table sales.sale_payments add column channel text;

alter table sales.sale_returns drop constraint if exists sale_returns_refund_method_check;
alter table sales.sale_returns
  add constraint sale_returns_refund_method_check
  check (refund_method in ('CASH', 'CARD', 'MOBILE_MONEY', 'INSURANCE', 'CREDIT', 'ACCOUNT_CREDIT',
                           'BANK_TRANSFER', 'CHEQUE', 'VOUCHER'));

create table sales.vouchers (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  code            text not null,
  initial_value   numeric(14,2) not null check (initial_value > 0),
  balance         numeric(14,2) not null check (balance >= 0),
  expires_on      date,
  holder_name     text,
  paid_by         text not null,
  status          text not null check (status in ('ACTIVE', 'SPENT', 'VOID')),
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0
);
create unique index vouchers_unique_code on sales.vouchers (tenant_uid, upper(code));
alter table sales.vouchers enable row level security;
alter table sales.vouchers force row level security;
create policy tenant_isolation on sales.vouchers
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.vouchers to ${application_role};
