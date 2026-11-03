-- receivables: customer credit accounts, their entries (signed, base currency) and the allocations
-- of credits to debits. Every table carries tenant_uid with forced row-level security.

grant usage on schema receivables to ${application_role};

create table receivables.accounts (
  id            bigint generated always as identity primary key,
  uid           uuid not null unique,
  tenant_uid    uuid not null,
  customer_uid  uuid not null,
  customer_name text not null,
  credit_limit  numeric(14, 2) not null default 0,
  terms_days    integer not null default 30,
  on_hold       boolean not null default false,
  hold_reason   text,
  created_at    timestamptz not null,
  created_by    text not null,
  updated_at    timestamptz,
  updated_by    text,
  version       bigint not null default 0,
  unique (tenant_uid, customer_uid)
);
create index accounts_by_name on receivables.accounts (tenant_uid, lower(customer_name));
alter table receivables.accounts enable row level security;
alter table receivables.accounts force row level security;
create policy tenant_isolation on receivables.accounts
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on receivables.accounts to ${application_role};

-- amount: debits (CHARGE, CREDIT_APPLIED) positive, credits (PAYMENT, CREDIT_NOTE, WRITE_OFF)
-- negative. open_amount: unpaid of a debit, unallocated of a credit; only it ever changes.
create table receivables.entries (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  customer_uid   uuid not null,
  type           text not null check (type in ('CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'WRITE_OFF', 'ADJUSTMENT', 'CREDIT_APPLIED')),
  reference      text,
  sale_uid       uuid,
  amount         numeric(14, 2) not null check (amount <> 0),
  open_amount    numeric(14, 2) not null check (open_amount >= 0 and open_amount <= abs(amount)),
  entry_date     date not null,
  due_date       date,
  note           text,
  method         text,
  currency       text,
  foreign_amount numeric(14, 2),
  exchange_rate  numeric(18, 8),
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0
);
create index entries_by_customer on receivables.entries (tenant_uid, customer_uid, entry_date, id);
create index entries_open on receivables.entries (tenant_uid, customer_uid) where open_amount > 0;
create index entries_by_sale on receivables.entries (tenant_uid, sale_uid) where sale_uid is not null;
alter table receivables.entries enable row level security;
alter table receivables.entries force row level security;
create policy tenant_isolation on receivables.entries
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
-- Update only moves open_amount (and the version columns); there is no delete.
grant select, insert, update on receivables.entries to ${application_role};

create table receivables.allocations (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  credit_entry_id bigint not null references receivables.entries (id),
  debit_entry_id  bigint not null references receivables.entries (id),
  amount          numeric(14, 2) not null check (amount > 0),
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0
);
create index allocations_by_credit on receivables.allocations (credit_entry_id);
create index allocations_by_debit on receivables.allocations (debit_entry_id);
create trigger allocations_are_append_only
  before update or delete on receivables.allocations
  for each row execute function platform.refuse_change();
alter table receivables.allocations enable row level security;
alter table receivables.allocations force row level security;
create policy tenant_isolation on receivables.allocations
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on receivables.allocations to ${application_role};
