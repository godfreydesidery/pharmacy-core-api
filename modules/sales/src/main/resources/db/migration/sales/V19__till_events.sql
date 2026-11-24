-- FRD-20 (adversarial review 23 Nov 2026): the server sees the sales that did not happen. The till
-- reports a cart cancelled or cleared with lines on it, a parked sale thrown away, and a drawer
-- opened with no sale; the report counts them, with their lines and value, per cashier and day.
--   kind         CART_CANCELLED, CART_CLEARED, PARKED_DISCARDED or NO_SALE
--   lines/value  what was on the cart (0 for a no-sale), the value in the base currency
--   occurred_on  the pharmacy's local day the till reported it
--   shift_uid    the cashier's open shift at the branch, if any
create table sales.till_events (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  created_at     timestamptz not null,
  created_by     text not null,
  created_by_uid uuid,
  updated_at     timestamptz,
  updated_by     text,
  updated_by_uid uuid,
  version        bigint not null default 0,
  branch_uid     uuid not null,
  shift_uid      uuid,
  kind           text not null
    check (kind in ('CART_CANCELLED', 'CART_CLEARED', 'PARKED_DISCARDED', 'NO_SALE')),
  lines          integer not null default 0 check (lines between 0 and 1000),
  value          numeric(14,2) not null default 0 check (value >= 0),
  reason         text,
  cashier_name   text,
  occurred_on    date not null
);
create index till_events_by_day on sales.till_events (tenant_uid, occurred_on, branch_uid);
alter table sales.till_events enable row level security;
alter table sales.till_events force row level security;
create policy tenant_isolation on sales.till_events
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on sales.till_events to ${application_role};
