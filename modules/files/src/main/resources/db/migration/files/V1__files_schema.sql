-- files: the record of each stored file of a tenant. The bytes live in the object store (local disk
-- or S3-compatible) under storage_key = tenants/{tenant_uid}/{yyyy}/{mm}/{uid}.

grant usage on schema files to ${application_role};

create table files.files (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  tenant_uid   uuid not null,
  name         text not null,
  content_type text not null,
  size_bytes   bigint not null,
  owner_type   text not null,
  owner_uid    uuid,
  purpose      text not null,
  storage_key  text,
  storage_name text not null,
  sha256       text,
  status       text not null check (status in ('PENDING', 'COMPLETE', 'DELETED')),
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0
);
create unique index files_by_storage_key on files.files (storage_key) where storage_key is not null;
create index files_by_tenant_owner on files.files (tenant_uid, owner_type, owner_uid, id);
alter table files.files enable row level security;
alter table files.files force row level security;
create policy tenant_isolation on files.files
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on files.files to ${application_role};
