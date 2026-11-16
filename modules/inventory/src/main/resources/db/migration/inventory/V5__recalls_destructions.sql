-- Supplier returns, batch recalls and the destruction register.
--
-- A return to the supplier takes stock out of a batch as a SUPPLIER_RETURN movement. A recalled
-- batch is quarantined across the tenant: it stays on hand (and on the books) but is never sold,
-- transferred or used in a transformation until released. A destruction records expired or damaged
-- stock destroyed before a witness, each line written off as an adjustment.

alter table inventory.movements drop constraint if exists movements_type_check;
alter table inventory.movements
  add constraint movements_type_check
  check (type in ('OPENING', 'RECEIPT', 'SALE', 'SALE_RETURN', 'ADJUSTMENT', 'TRANSFER_OUT',
                  'TRANSFER_IN', 'TRANSFORM_OUT', 'TRANSFORM_IN', 'SUPPLIER_RETURN'));

alter table inventory.batches
  add column quarantined        boolean not null default false,
  add column quarantine_reason  text,
  add column quarantined_at     timestamptz,
  add column quarantined_by     text;

create table inventory.destructions (
  id                  bigint generated always as identity primary key,
  uid                 uuid not null unique,
  tenant_uid          uuid not null,
  created_at          timestamptz not null,
  created_by          text not null,
  updated_at          timestamptz,
  updated_by          text,
  version             bigint not null default 0,
  number              text not null,
  branch_uid          uuid not null,
  destroyed_on        date not null,
  method              text not null,
  witness_name        text not null,
  witness_title       text,
  certificate_number  text,
  note                text,
  total_cost          numeric(14,2) not null,
  unique (tenant_uid, number)
);
create index destructions_by_tenant_branch on inventory.destructions (tenant_uid, branch_uid, id);
alter table inventory.destructions enable row level security;
alter table inventory.destructions force row level security;
create policy tenant_isolation on inventory.destructions
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on inventory.destructions to ${application_role};

create table inventory.destruction_lines (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0,
  destruction_id  bigint not null references inventory.destructions (id),
  product_uid     uuid not null,
  product_name    text not null,
  batch_uid       uuid not null,
  batch_number    text not null,
  expiry_date     date,
  reason          text not null check (reason in ('EXPIRED', 'DAMAGED')),
  quantity        integer not null check (quantity > 0),
  unit_cost       numeric(14,2) not null
);
alter table inventory.destruction_lines enable row level security;
alter table inventory.destruction_lines force row level security;
create policy tenant_isolation on inventory.destruction_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on inventory.destruction_lines to ${application_role};
