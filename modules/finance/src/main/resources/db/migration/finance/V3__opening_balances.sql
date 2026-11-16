-- A pharmacy's opening balances, once per tenant: cash, bank, mobile money, stock at cost, what
-- customers owe and what is owed to suppliers. Owner's equity takes the difference. The journal
-- entry is the posting; these rows keep the figures and the parties behind them.

create table finance.opening_balances (
  id            bigint generated always as identity primary key,
  uid           uuid not null unique,
  tenant_uid    uuid not null unique,
  as_of         date not null,
  cash          numeric(14, 2) not null default 0,
  bank          numeric(14, 2) not null default 0,
  mobile_money  numeric(14, 2) not null default 0,
  inventory     numeric(14, 2) not null default 0,
  receivables   numeric(14, 2) not null default 0,
  payables      numeric(14, 2) not null default 0,
  capital       numeric(14, 2) not null,
  journal_number text,
  created_at    timestamptz not null,
  created_by    text not null,
  updated_at    timestamptz,
  updated_by    text,
  version       bigint not null default 0
);
alter table finance.opening_balances enable row level security;
alter table finance.opening_balances force row level security;
create policy tenant_isolation on finance.opening_balances
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on finance.opening_balances to ${application_role};

create table finance.opening_balance_lines (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  opening_id  bigint not null references finance.opening_balances (id),
  party_type  text not null check (party_type in ('CUSTOMER', 'SUPPLIER')),
  party_uid   uuid not null,
  party_name  text not null,
  amount      numeric(14, 2) not null check (amount > 0),
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0
);
alter table finance.opening_balance_lines enable row level security;
alter table finance.opening_balance_lines force row level security;
create policy tenant_isolation on finance.opening_balance_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on finance.opening_balance_lines to ${application_role};
