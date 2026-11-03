-- Platform-wide helpers that every module's policies call, and the platform's own infrastructure
-- tables (no domain data).

grant usage on schema platform to ${application_role};

-- The tenant of the current transaction, set with set_config(..., true) by the application
-- (TenantAwareTransactionManager). An unset or empty setting is NULL, which matches no row:
-- isolation fails closed.
create function platform.current_tenant_uid() returns uuid
  language sql stable
as $$ select nullif(current_setting('pharmacy.tenant_uid', true), '')::uuid $$;

-- The signed-in user of the current transaction: a user may read their own memberships in every
-- tenant, to sign in and to choose a workspace.
create function platform.current_user_uid() returns uuid
  language sql stable
as $$ select nullif(current_setting('pharmacy.user_uid', true), '')::uuid $$;

-- Second line of defence for append-only tables, behind the missing update/delete grants.
create function platform.refuse_change() returns trigger
  language plpgsql
as $$
begin
  raise exception '% is append-only', tg_table_name using errcode = 'P0001';
end
$$;

-- Spring Modulith 2 event publication registry: the transactional outbox. An event is stored with
-- the transaction that published it and marked completed once each listener has handled it.
create table platform.event_publication (
  id                     uuid primary key,
  listener_id            text not null,
  event_type             text not null,
  serialized_event       text not null,
  publication_date       timestamptz not null,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    int,
  last_resubmission_date timestamptz
);
create index event_publication_serialized_event_hash_idx
  on platform.event_publication using hash (serialized_event);
create index event_publication_by_completion_date_idx
  on platform.event_publication (completion_date);
grant select, insert, update, delete on platform.event_publication to ${application_role};

create table platform.event_publication_archive (
  id                     uuid primary key,
  listener_id            text not null,
  event_type             text not null,
  serialized_event       text not null,
  publication_date       timestamptz not null,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    int,
  last_resubmission_date timestamptz
);
grant select, insert, update, delete on platform.event_publication_archive to ${application_role};

-- Document numbers per tenant and key, such as INV-HQ (DocumentNumbers).
create table platform.document_sequences (
  tenant_uid   uuid not null,
  sequence_key text not null,
  last_value   bigint not null,
  primary key (tenant_uid, sequence_key)
);
alter table platform.document_sequences enable row level security;
alter table platform.document_sequences force row level security;
create policy tenant_isolation on platform.document_sequences
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on platform.document_sequences to ${application_role};
