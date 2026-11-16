-- Stock-takes: a count sheet of a branch's stock per batch, filled in with what was counted, and
-- posted as count corrections for the differences.

create table inventory.stock_counts (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  number      text not null,
  branch_uid  uuid not null,
  status      text not null check (status in ('OPEN', 'POSTED')),
  note        text,
  posted_at   timestamptz,
  posted_by   text,
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0,
  unique (tenant_uid, number)
);
alter table inventory.stock_counts enable row level security;
alter table inventory.stock_counts force row level security;
create policy tenant_isolation on inventory.stock_counts
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on inventory.stock_counts to ${application_role};

create table inventory.stock_count_lines (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  stock_count_id  bigint not null references inventory.stock_counts (id),
  product_uid     uuid not null,
  product_name    text not null,
  batch_uid       uuid not null,
  batch_number    text not null,
  expiry_date     date,
  unit_cost       numeric(14,4) not null,
  expected        integer not null,
  counted         integer check (counted >= 0),
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0
);
alter table inventory.stock_count_lines enable row level security;
alter table inventory.stock_count_lines force row level security;
create policy tenant_isolation on inventory.stock_count_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on inventory.stock_count_lines to ${application_role};
