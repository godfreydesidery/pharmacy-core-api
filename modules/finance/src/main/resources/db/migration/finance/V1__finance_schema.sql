-- finance: the chart of accounts, the double-entry journal and expenses.

grant usage on schema finance to ${application_role};

-- The tenant's chart, seeded with the default accounts on first use.
create table finance.accounts (
  id         bigint generated always as identity primary key,
  uid        uuid not null unique,
  tenant_uid uuid not null,
  code       text not null,
  name       text not null,
  type       text not null check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
  created_at timestamptz not null,
  created_by text not null,
  updated_at timestamptz,
  updated_by text,
  version    bigint not null default 0,
  unique (tenant_uid, code)
);
alter table finance.accounts enable row level security;
alter table finance.accounts force row level security;
create policy tenant_isolation on finance.accounts
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on finance.accounts to ${application_role};

-- One entry per source document: (tenant, source type, source uid) is unique, so a redelivered
-- event never posts twice. Entries and lines are append-only.
create table finance.journal_entries (
  id               bigint generated always as identity primary key,
  uid              uuid not null unique,
  tenant_uid       uuid not null,
  number           text not null,
  entry_date       date not null,
  source_type      text not null,
  source_uid       uuid not null,
  source_reference text,
  description      text not null,
  branch_uid       uuid,
  created_at       timestamptz not null,
  created_by       text not null,
  updated_at       timestamptz,
  updated_by       text,
  version          bigint not null default 0,
  unique (tenant_uid, number),
  unique (tenant_uid, source_type, source_uid)
);
create index journal_entries_by_tenant_entry_date
  on finance.journal_entries (tenant_uid, entry_date);
create trigger journal_entries_are_append_only
  before update or delete on finance.journal_entries
  for each row execute function platform.refuse_change();
alter table finance.journal_entries enable row level security;
alter table finance.journal_entries force row level security;
create policy tenant_isolation on finance.journal_entries
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on finance.journal_entries to ${application_role};

create table finance.journal_lines (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  tenant_uid   uuid not null,
  entry_id     bigint not null references finance.journal_entries (id),
  account_code text not null,
  account_name text not null,
  debit        numeric(14,2) not null default 0 check (debit >= 0),
  credit       numeric(14,2) not null default 0 check (credit >= 0),
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0
);
create index journal_lines_by_entry_id on finance.journal_lines (entry_id);
create index journal_lines_by_tenant_account on finance.journal_lines (tenant_uid, account_code);
create trigger journal_lines_are_append_only
  before update or delete on finance.journal_lines
  for each row execute function platform.refuse_change();
alter table finance.journal_lines enable row level security;
alter table finance.journal_lines force row level security;
create policy tenant_isolation on finance.journal_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on finance.journal_lines to ${application_role};

create table finance.expenses (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  number         text not null,
  branch_uid     uuid not null,
  category       text not null,
  description    text not null,
  amount         numeric(14,2) not null check (amount > 0),
  expense_date   date not null,
  payment_method text not null,
  reference      text,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0,
  unique (tenant_uid, number)
);
create index expenses_by_tenant_expense_date on finance.expenses (tenant_uid, expense_date);
alter table finance.expenses enable row level security;
alter table finance.expenses force row level security;
create policy tenant_isolation on finance.expenses
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on finance.expenses to ${application_role};
