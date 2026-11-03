-- inventory: product snapshots, batches, stock per branch, the stock ledger and transfers.
-- Every table is tenant-scoped with row-level security on tenant_uid.

grant usage on schema inventory to ${application_role};

-- What inventory knows of a catalogue product, kept current from product.changed.
create table inventory.products (
  id            bigint generated always as identity primary key,
  uid           uuid not null unique,
  tenant_uid    uuid not null,
  created_at    timestamptz not null,
  created_by    text not null,
  updated_at    timestamptz,
  updated_by    text,
  version       bigint not null default 0,
  product_uid   uuid not null,
  name          text not null,
  generic_name  text,
  sku           text,
  dosage_form   text,
  strength      text,
  category_uid  uuid,
  category_name text,
  reorder_level integer not null default 0,
  selling_price numeric(14,2) not null default 0,
  active        boolean not null default true,
  unique (tenant_uid, product_uid)
);
create index products_by_tenant_name on inventory.products (tenant_uid, lower(name));
alter table inventory.products enable row level security;
alter table inventory.products force row level security;
create policy tenant_isolation on inventory.products
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on inventory.products to ${application_role};

-- A batch (lot) of a product: one expiry and one unit cost, shared by every branch holding it.
create table inventory.batches (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  tenant_uid   uuid not null,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0,
  product_uid  uuid not null,
  batch_number text not null,
  expiry_date  date not null,
  unit_cost    numeric(14,2) not null,
  unique (tenant_uid, product_uid, batch_number)
);
create index batches_by_tenant_expiry on inventory.batches (tenant_uid, expiry_date);
alter table inventory.batches enable row level security;
alter table inventory.batches force row level security;
create policy tenant_isolation on inventory.batches
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
-- update: FEFO allocation locks batches with the stock rows (select ... for update).
grant select, insert, update on inventory.batches to ${application_role};

-- The quantity of one batch at one branch; changed only together with a movement.
create table inventory.stock_levels (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0,
  branch_uid  uuid not null,
  batch_id    bigint not null references inventory.batches (id),
  product_uid uuid not null,
  quantity    integer not null check (quantity >= 0),
  unique (tenant_uid, branch_uid, batch_id)
);
create index stock_levels_by_tenant_branch_product
  on inventory.stock_levels (tenant_uid, branch_uid, product_uid);
create index stock_levels_by_batch on inventory.stock_levels (batch_id);
alter table inventory.stock_levels enable row level security;
alter table inventory.stock_levels force row level security;
create policy tenant_isolation on inventory.stock_levels
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on inventory.stock_levels to ${application_role};

-- The stock ledger: every change of stock, append-only. Current stock is the stock levels; the
-- ledger explains how each got there.
create table inventory.movements (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0,
  branch_uid      uuid not null,
  product_uid     uuid not null,
  product_name    text not null,
  batch_uid       uuid not null,
  batch_number    text not null,
  type            text not null check (type in ('OPENING', 'RECEIPT', 'SALE', 'SALE_RETURN',
                                                'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN')),
  quantity_change integer not null,
  balance_after   integer not null,
  reference       text,
  reason_code     text,
  note            text
);
create index movements_by_tenant_branch_product_uid
  on inventory.movements (tenant_uid, branch_uid, product_uid, uid);
create index movements_by_tenant_branch_uid on inventory.movements (tenant_uid, branch_uid, uid);
create trigger movements_are_append_only
  before update or delete on inventory.movements
  for each row execute function platform.refuse_change();
alter table inventory.movements enable row level security;
alter table inventory.movements force row level security;
create policy tenant_isolation on inventory.movements
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on inventory.movements to ${application_role};

create table inventory.transfers (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0,
  number          text not null,
  from_branch_uid uuid not null,
  to_branch_uid   uuid not null,
  status          text not null check (status in ('IN_TRANSIT', 'RECEIVED')),
  note            text,
  received_at     timestamptz,
  unique (tenant_uid, number)
);
create index transfers_by_tenant_from on inventory.transfers (tenant_uid, from_branch_uid, uid);
create index transfers_by_tenant_to on inventory.transfers (tenant_uid, to_branch_uid, uid);
alter table inventory.transfers enable row level security;
alter table inventory.transfers force row level security;
create policy tenant_isolation on inventory.transfers
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on inventory.transfers to ${application_role};

create table inventory.transfer_lines (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  tenant_uid   uuid not null,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0,
  transfer_id  bigint not null references inventory.transfers (id),
  product_uid  uuid not null,
  product_name text not null,
  batch_uid    uuid not null,
  batch_number text not null,
  expiry_date  date not null,
  quantity     integer not null check (quantity > 0)
);
create index transfer_lines_by_transfer on inventory.transfer_lines (transfer_id);
alter table inventory.transfer_lines enable row level security;
alter table inventory.transfer_lines force row level security;
create policy tenant_isolation on inventory.transfer_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on inventory.transfer_lines to ${application_role};
