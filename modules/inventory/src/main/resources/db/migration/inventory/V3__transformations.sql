-- Transformations: stock of some products turned into stock of another at one branch. Breaking a
-- box into strips, repacking bulk into the pharmacy's own packs, and compounding a preparation from
-- ingredients. The inputs leave as TRANSFORM_OUT movements (earliest expiry first); the output
-- arrives as a TRANSFORM_IN movement on a batch that keeps the earliest input expiry.

alter table inventory.movements drop constraint if exists movements_type_check;
alter table inventory.movements
  add constraint movements_type_check
  check (type in ('OPENING', 'RECEIPT', 'SALE', 'SALE_RETURN', 'ADJUSTMENT', 'TRANSFER_OUT',
                  'TRANSFER_IN', 'TRANSFORM_OUT', 'TRANSFORM_IN'));

create table inventory.transformations (
  id                   bigint generated always as identity primary key,
  uid                  uuid not null unique,
  tenant_uid           uuid not null,
  created_at           timestamptz not null,
  created_by           text not null,
  updated_at           timestamptz,
  updated_by           text,
  version              bigint not null default 0,
  number               text not null,
  branch_uid           uuid not null,
  type                 text not null check (type in ('BREAK_BULK', 'REPACK', 'COMPOUND')),
  note                 text,
  output_product_uid   uuid not null,
  output_product_name  text not null,
  output_batch_uid     uuid not null,
  output_batch_number  text not null,
  output_expiry_date   date,
  output_quantity      integer not null check (output_quantity > 0),
  output_unit_cost     numeric(14,4) not null,
  unique (tenant_uid, number)
);
create index transformations_by_tenant_branch on inventory.transformations (tenant_uid, branch_uid, id);
alter table inventory.transformations enable row level security;
alter table inventory.transformations force row level security;
create policy tenant_isolation on inventory.transformations
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on inventory.transformations to ${application_role};

create table inventory.transformation_inputs (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  tenant_uid         uuid not null,
  created_at         timestamptz not null,
  created_by         text not null,
  updated_at         timestamptz,
  updated_by         text,
  version            bigint not null default 0,
  transformation_id  bigint not null references inventory.transformations (id),
  product_uid        uuid not null,
  product_name       text not null,
  batch_uid          uuid not null,
  batch_number       text not null,
  expiry_date        date,
  quantity           integer not null check (quantity > 0),
  unit_cost          numeric(14,4) not null
);
alter table inventory.transformation_inputs enable row level security;
alter table inventory.transformation_inputs force row level security;
create policy tenant_isolation on inventory.transformation_inputs
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on inventory.transformation_inputs to ${application_role};
