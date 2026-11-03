-- sales: sales (invoices) with their lines and payments, and sales returns. Every table belongs to
-- one tenant and carries the row-level security policy that hides every other tenant's rows.

grant usage on schema sales to ${application_role};

create table sales.sales (
  id                      bigint generated always as identity primary key,
  uid                     uuid not null unique,
  tenant_uid              uuid not null,
  created_at              timestamptz not null,
  created_by              text not null,
  updated_at              timestamptz,
  updated_by              text,
  version                 bigint not null default 0,
  number                  text not null,
  branch_uid              uuid not null,
  branch_name             text,
  customer_uid            uuid,
  customer_name           text,
  status                  text not null
                            check (status in ('COMPLETED', 'PARTIALLY_RETURNED', 'RETURNED')),
  discount_percent        numeric(5,2) not null default 0,
  subtotal                numeric(14,2) not null,
  discount_total          numeric(14,2) not null,
  tax_total               numeric(14,2) not null,
  total                   numeric(14,2) not null,
  paid_total              numeric(14,2) not null,
  change_due              numeric(14,2) not null,
  idempotency_key         text,
  prescriber_name         text,
  prescriber_registration text,
  prescription_reference  text,
  patient_name            text,
  fiscal_reference        text,
  cashier_name            text,
  constraint sales_number_unique unique (tenant_uid, number)
);
-- A repeated idempotency key returns the first sale instead of selling twice (offline POS sync).
create unique index sales_by_tenant_idempotency_key
  on sales.sales (tenant_uid, idempotency_key) where idempotency_key is not null;
create index sales_by_tenant_branch_created_at on sales.sales (tenant_uid, branch_uid, created_at);
alter table sales.sales enable row level security;
alter table sales.sales force row level security;
create policy tenant_isolation on sales.sales
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.sales to ${application_role};

create table sales.sale_lines (
  id                bigint generated always as identity primary key,
  uid               uuid not null unique,
  tenant_uid        uuid not null,
  created_at        timestamptz not null,
  created_by        text not null,
  updated_at        timestamptz,
  updated_by        text,
  version           bigint not null default 0,
  sale_id           bigint not null references sales.sales (id),
  product_uid       uuid not null,
  product_name      text not null,
  batch_uid         uuid not null,
  batch_number      text,
  expiry_date       date,
  quantity          integer not null check (quantity > 0),
  returned_quantity integer not null default 0 check (returned_quantity >= 0),
  unit_price        numeric(14,2) not null,
  unit_cost         numeric(14,2) not null,
  tax_rate_percent  numeric(5,2) not null,
  discount          numeric(14,2) not null,
  tax               numeric(14,2) not null,
  line_total        numeric(14,2) not null,
  check (returned_quantity <= quantity)
);
create index sale_lines_by_sale_id on sales.sale_lines (sale_id);
create index sale_lines_by_tenant_product_uid on sales.sale_lines (tenant_uid, product_uid);
alter table sales.sale_lines enable row level security;
alter table sales.sale_lines force row level security;
create policy tenant_isolation on sales.sale_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.sale_lines to ${application_role};

create table sales.sale_payments (
  id         bigint generated always as identity primary key,
  uid        uuid not null unique,
  tenant_uid uuid not null,
  created_at timestamptz not null,
  created_by text not null,
  updated_at timestamptz,
  updated_by text,
  version    bigint not null default 0,
  sale_id    bigint not null references sales.sales (id),
  method     text not null
               check (method in ('CASH', 'CARD', 'MOBILE_MONEY', 'INSURANCE', 'CREDIT')),
  amount     numeric(14,2) not null check (amount > 0),
  reference  text
);
create index sale_payments_by_sale_id on sales.sale_payments (sale_id);
alter table sales.sale_payments enable row level security;
alter table sales.sale_payments force row level security;
create policy tenant_isolation on sales.sale_payments
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.sale_payments to ${application_role};

create table sales.sale_returns (
  id            bigint generated always as identity primary key,
  uid           uuid not null unique,
  tenant_uid    uuid not null,
  created_at    timestamptz not null,
  created_by    text not null,
  updated_at    timestamptz,
  updated_by    text,
  version       bigint not null default 0,
  number        text not null,
  branch_uid    uuid not null,
  sale_uid      uuid not null,
  sale_number   text not null,
  reason        text not null,
  refund_method text not null
                  check (refund_method in ('CASH', 'CARD', 'MOBILE_MONEY', 'INSURANCE', 'CREDIT')),
  refund_total  numeric(14,2) not null,
  constraint sale_returns_number_unique unique (tenant_uid, number)
);
create index sale_returns_by_tenant_branch_created_at
  on sales.sale_returns (tenant_uid, branch_uid, created_at);
create index sale_returns_by_sale_uid on sales.sale_returns (sale_uid);
alter table sales.sale_returns enable row level security;
alter table sales.sale_returns force row level security;
create policy tenant_isolation on sales.sale_returns
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.sale_returns to ${application_role};

create table sales.sale_return_lines (
  id            bigint generated always as identity primary key,
  uid           uuid not null unique,
  tenant_uid    uuid not null,
  created_at    timestamptz not null,
  created_by    text not null,
  updated_at    timestamptz,
  updated_by    text,
  version       bigint not null default 0,
  return_id     bigint not null references sales.sale_returns (id),
  sale_line_uid uuid not null,
  product_uid   uuid not null,
  product_name  text not null,
  batch_uid     uuid not null,
  batch_number  text,
  quantity      integer not null check (quantity > 0),
  refund        numeric(14,2) not null,
  tax           numeric(14,2) not null,
  cost          numeric(14,2) not null
);
create index sale_return_lines_by_return_id on sales.sale_return_lines (return_id);
alter table sales.sale_return_lines enable row level security;
alter table sales.sale_return_lines force row level security;
create policy tenant_isolation on sales.sale_return_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.sale_return_lines to ${application_role};
