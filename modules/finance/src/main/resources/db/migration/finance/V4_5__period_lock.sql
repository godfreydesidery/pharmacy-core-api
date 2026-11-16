-- The date through which a pharmacy's books are closed: nothing may be posted on or before it.
-- One row per tenant; no row, or a null date, means no period is locked.

create table finance.period_locks (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null unique,
  locked_through date,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0
);
alter table finance.period_locks enable row level security;
alter table finance.period_locks force row level security;
create policy tenant_isolation on finance.period_locks
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on finance.period_locks to ${application_role};
