-- Tracking modes: medicines are tracked by batch and expiry (sold earliest expiry first); supplies
-- and cosmetics by batch without expiry (first in, first out); simple goods by quantity alone
-- (one implicit batch "-"); equipment such as wheelchairs unit by unit, by serial number.

alter table inventory.products
  add column product_type    text not null default 'MEDICINE',
  add column tracking_mode   text not null default 'BATCH_EXPIRY'
    check (tracking_mode in ('BATCH_EXPIRY', 'BATCH', 'SERIAL', 'QUANTITY')),
  add column unit_of_measure text not null default 'PIECE';
create index products_by_tenant_type on inventory.products (tenant_uid, product_type);

-- A batch of a product without expiry has none.
alter table inventory.batches alter column expiry_date drop not null;
alter table inventory.transfer_lines alter column expiry_date drop not null;

-- The serial numbers each movement and transfer line moved, comma-separated.
alter table inventory.movements add column serial_numbers text;
alter table inventory.transfer_lines add column serial_numbers text;

-- One unit of a serial-tracked product, such as wheelchair WCH-0001: where it is and what became
-- of it. A unit belongs to the batch it was received in, for its unit cost.
create table inventory.serial_units (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0,
  product_uid    uuid not null,
  batch_id       bigint not null references inventory.batches (id),
  serial_number  text not null,
  branch_uid     uuid not null,
  status         text not null
    check (status in ('IN_STOCK', 'SOLD', 'RETURNED', 'IN_TRANSIT', 'WRITTEN_OFF')),
  sale_reference text,
  sold_at        timestamptz,
  unique (tenant_uid, product_uid, serial_number)
);
create index serial_units_by_tenant_branch_product_status
  on inventory.serial_units (tenant_uid, branch_uid, product_uid, status);
create index serial_units_by_batch on inventory.serial_units (batch_id);
alter table inventory.serial_units enable row level security;
alter table inventory.serial_units force row level security;
create policy tenant_isolation on inventory.serial_units
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on inventory.serial_units to ${application_role};
