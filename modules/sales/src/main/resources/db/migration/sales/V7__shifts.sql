-- Till shifts: a cashier opens with a float and closes by counting the cash; the close compares it
-- with what the sales say should be there (float + cash taken − change − cash refunds).

create table sales.shifts (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  branch_uid     uuid not null,
  cashier        text not null,
  cashier_name   text,
  status         text not null check (status in ('OPEN', 'CLOSED')),
  opened_at      timestamptz not null,
  opening_float  numeric(14,2) not null check (opening_float >= 0),
  closed_at      timestamptz,
  expected_cash  numeric(14,2),
  counted_cash   numeric(14,2),
  variance       numeric(14,2),
  note           text,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0
);
create unique index shifts_one_open_per_cashier
  on sales.shifts (tenant_uid, branch_uid, cashier) where status = 'OPEN';
alter table sales.shifts enable row level security;
alter table sales.shifts force row level security;
create policy tenant_isolation on sales.shifts
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.shifts to ${application_role};
