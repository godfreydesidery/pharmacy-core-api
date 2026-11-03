-- purchasing: purchase orders and goods receipts. Tenant-scoped, with row-level security.

grant usage on schema purchasing to ${application_role};

create table purchasing.purchase_orders (
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
  supplier_uid  uuid not null,
  supplier_name text not null,
  status        text not null check (status in ('ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
  expected_date date,
  note          text,
  cancel_reason text,
  total         numeric(14,2) not null,
  unique (tenant_uid, number)
);
create index purchase_orders_by_tenant_branch_uid
  on purchasing.purchase_orders (tenant_uid, branch_uid, uid);
alter table purchasing.purchase_orders enable row level security;
alter table purchasing.purchase_orders force row level security;
create policy tenant_isolation on purchasing.purchase_orders
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on purchasing.purchase_orders to ${application_role};

create table purchasing.purchase_order_lines (
  id                bigint generated always as identity primary key,
  uid               uuid not null unique,
  tenant_uid        uuid not null,
  created_at        timestamptz not null,
  created_by        text not null,
  updated_at        timestamptz,
  updated_by        text,
  version           bigint not null default 0,
  purchase_order_id bigint not null references purchasing.purchase_orders (id),
  product_uid       uuid not null,
  product_name      text not null,
  quantity          integer not null check (quantity > 0),
  received_quantity integer not null default 0 check (received_quantity >= 0),
  unit_cost         numeric(14,2) not null,
  line_total        numeric(14,2) not null
);
create index purchase_order_lines_by_order on purchasing.purchase_order_lines (purchase_order_id);
alter table purchasing.purchase_order_lines enable row level security;
alter table purchasing.purchase_order_lines force row level security;
create policy tenant_isolation on purchasing.purchase_order_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on purchasing.purchase_order_lines to ${application_role};

create table purchasing.goods_receipts (
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
  purchase_order_uid      uuid,
  purchase_order_number   text,
  supplier_uid            uuid not null,
  supplier_name           text not null,
  supplier_invoice_number text,
  note                    text,
  total                   numeric(14,2) not null,
  unique (tenant_uid, number)
);
create index goods_receipts_by_tenant_branch_uid
  on purchasing.goods_receipts (tenant_uid, branch_uid, uid);
create index goods_receipts_by_tenant_created on purchasing.goods_receipts (tenant_uid, created_at);
alter table purchasing.goods_receipts enable row level security;
alter table purchasing.goods_receipts force row level security;
create policy tenant_isolation on purchasing.goods_receipts
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on purchasing.goods_receipts to ${application_role};

create table purchasing.goods_receipt_lines (
  id               bigint generated always as identity primary key,
  uid              uuid not null unique,
  tenant_uid       uuid not null,
  created_at       timestamptz not null,
  created_by       text not null,
  updated_at       timestamptz,
  updated_by       text,
  version          bigint not null default 0,
  goods_receipt_id bigint not null references purchasing.goods_receipts (id),
  product_uid      uuid not null,
  product_name     text not null,
  batch_number     text not null,
  expiry_date      date not null,
  quantity         integer not null check (quantity > 0),
  unit_cost        numeric(14,2) not null,
  line_total       numeric(14,2) not null
);
create index goods_receipt_lines_by_receipt on purchasing.goods_receipt_lines (goods_receipt_id);
alter table purchasing.goods_receipt_lines enable row level security;
alter table purchasing.goods_receipt_lines force row level security;
create policy tenant_isolation on purchasing.goods_receipt_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on purchasing.goods_receipt_lines to ${application_role};
