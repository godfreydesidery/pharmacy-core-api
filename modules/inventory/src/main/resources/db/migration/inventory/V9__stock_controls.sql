-- Batch 4, the owner's stock controls (FRD-5, FRD-15). New columns only; no existing row changes.
--  * destructions: the manager whose approval a destruction used is its witness; the name typed
--    stays in witness_name, which may now be empty when a manager approved.
--  * transfers: who received a transfer (never who dispatched it).
--  * transfer_lines.received_quantity: the units that arrived; null until received, and on
--    transfers received before lines were received one by one (they arrived in full).
alter table inventory.destructions
  add column approved_by_name text,
  add column approved_by_uid  uuid;
alter table inventory.destructions alter column witness_name drop not null;

alter table inventory.transfers
  add column received_by     text,
  add column received_by_uid uuid;

alter table inventory.transfer_lines
  add column received_quantity integer check (received_quantity >= 0);

create index transfers_in_transit_by_age
  on inventory.transfers (tenant_uid, created_at) where status = 'IN_TRANSIT';
