-- reporting: read models built from events, one row per tenant, branch, local day and key. A
-- listener adds each fact with one atomic upsert; processed_events makes a redelivery a no-op.
-- Reports read these tables only, never another module's.

grant usage on schema reporting to ${application_role};

create table reporting.daily_sales (
  tenant_uid uuid not null,
  branch_uid uuid not null,
  day        date not null,
  invoices   integer not null default 0,
  gross      numeric(14,2) not null default 0,
  discount   numeric(14,2) not null default 0,
  tax        numeric(14,2) not null default 0,
  net        numeric(14,2) not null default 0,
  cost       numeric(14,2) not null default 0,
  primary key (tenant_uid, branch_uid, day)
);
create index daily_sales_by_tenant_day on reporting.daily_sales (tenant_uid, day);

create table reporting.daily_product_sales (
  tenant_uid   uuid not null,
  branch_uid   uuid not null,
  day          date not null,
  product_uid  uuid not null,
  product_name text not null,
  quantity     integer not null default 0,
  net          numeric(14,2) not null default 0,
  cost         numeric(14,2) not null default 0,
  primary key (tenant_uid, branch_uid, day, product_uid)
);
create index daily_product_sales_by_tenant_day on reporting.daily_product_sales (tenant_uid, day);

create table reporting.daily_payments (
  tenant_uid uuid not null,
  branch_uid uuid not null,
  day        date not null,
  method     text not null,
  amount     numeric(14,2) not null default 0,
  payments   integer not null default 0,
  primary key (tenant_uid, branch_uid, day, method)
);

create table reporting.daily_purchases (
  tenant_uid uuid not null,
  branch_uid uuid not null,
  day        date not null,
  receipts   integer not null default 0,
  total      numeric(14,2) not null default 0,
  primary key (tenant_uid, branch_uid, day)
);

create table reporting.daily_expenses (
  tenant_uid uuid not null,
  branch_uid uuid not null,
  day        date not null,
  category   text not null,
  amount     numeric(14,2) not null default 0,
  primary key (tenant_uid, branch_uid, day, category)
);

create table reporting.processed_events (
  tenant_uid   uuid not null,
  event_id     uuid not null,
  processed_at timestamptz not null,
  primary key (tenant_uid, event_id)
);

do $$
declare
  t text;
begin
  foreach t in array array['daily_sales', 'daily_product_sales', 'daily_payments',
                           'daily_purchases', 'daily_expenses', 'processed_events']
  loop
    execute format('alter table reporting.%I enable row level security', t);
    execute format('alter table reporting.%I force row level security', t);
    execute format('create policy tenant_isolation on reporting.%I'
                   ' using (tenant_uid = platform.current_tenant_uid())'
                   ' with check (tenant_uid = platform.current_tenant_uid())', t);
    execute format('grant select, insert, update on reporting.%I to ${application_role}', t);
  end loop;
end
$$;
