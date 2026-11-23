-- A second person's approval for a sensitive action (refund, write-off, discount, period unlock…).
-- One row per approval: the manager who approved, for whom, which action and up to what amount; it
-- is single use and lasts two minutes. Only the SHA-256 of its token is kept. Rows stay as the
-- record of who approved what (the audit trail has the same).
create table platform.approvals (
  id               bigint generated always as identity primary key,
  uid              uuid not null unique,
  tenant_uid       uuid not null,
  token_hash       text not null unique,
  action           text not null,
  amount           numeric(14,2) not null check (amount >= 0),
  reference        text,
  requested_by_uid uuid not null,
  approved_by_uid  uuid not null,
  approved_by_name text not null,
  branch_uid       uuid,
  created_at       timestamptz not null,
  expires_at       timestamptz not null,
  used_at          timestamptz,
  used_reference   text,
  check (approved_by_uid <> requested_by_uid)
);
create index approvals_by_tenant_created on platform.approvals (tenant_uid, created_at);
alter table platform.approvals enable row level security;
alter table platform.approvals force row level security;
create policy tenant_isolation on platform.approvals
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on platform.approvals to ${application_role};
