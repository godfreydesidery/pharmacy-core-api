-- STD-5 (Tanzania's Personal Data Protection Act 2022): per-person rights and consent records.

-- 1. An anonymised customer (erasure by anonymisation, verify with counsel: C10). The row stays,
-- because sales, claims and receivables point at it; its personal fields are replaced. Nullable
-- columns without defaults: no row is rewritten.
alter table partners.customers
  add column anonymised_at timestamptz,
  add column anonymised_by text;

-- 2. Consent per customer and purpose: given (when, by whom), withdrawn (when, by whom). Giving
-- again after a withdrawal is a new row, so the history stays whole. A row with no given_at is a
-- refusal noted when the customer was asked. At most one row in force per customer and purpose.
create table partners.customer_consents (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  customer_uid   uuid not null,
  purpose        text not null check (purpose in ('SMS_RECEIPTS', 'MARKETING', 'INSURER_SHARING')),
  given_at       timestamptz,
  given_by       text,
  withdrawn_at   timestamptz,
  withdrawn_by   text,
  note           text,
  created_at     timestamptz not null,
  created_by     text not null,
  created_by_uid uuid,
  updated_at     timestamptz,
  updated_by     text,
  updated_by_uid uuid,
  version        bigint not null default 0,
  check (given_at is not null or withdrawn_at is not null)
);
create index customer_consents_by_customer
  on partners.customer_consents (tenant_uid, customer_uid, purpose, id);
create unique index customer_consents_one_in_force
  on partners.customer_consents (tenant_uid, customer_uid, purpose) where withdrawn_at is null;
alter table partners.customer_consents enable row level security;
alter table partners.customer_consents force row level security;
create policy tenant_isolation on partners.customer_consents
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on partners.customer_consents to ${application_role};

-- 3. The register of data-subject requests: the right asked for, when received, when due (30 days
-- until counsel confirms, C7), who completed or refused it and why. No health data here.
create table partners.data_subject_requests (
  id               bigint generated always as identity primary key,
  uid              uuid not null unique,
  tenant_uid       uuid not null,
  number           text not null,
  kind             text not null check (kind in ('ACCESS', 'CORRECTION', 'ERASURE', 'OBJECTION', 'RESTRICTION')),
  status           text not null check (status in ('OPEN', 'COMPLETED', 'REFUSED')),
  customer_uid     uuid not null,
  received_at      timestamptz not null,
  due_on           date not null,
  completed_at     timestamptz,
  completed_by     text,
  completed_by_uid uuid,
  outcome          text,
  note             text,
  created_at       timestamptz not null,
  created_by       text not null,
  created_by_uid   uuid,
  updated_at       timestamptz,
  updated_by       text,
  updated_by_uid   uuid,
  version          bigint not null default 0,
  unique (tenant_uid, number)
);
create index data_subject_requests_by_tenant_uid
  on partners.data_subject_requests (tenant_uid, uid);
create index data_subject_requests_by_customer
  on partners.data_subject_requests (tenant_uid, customer_uid);
alter table partners.data_subject_requests enable row level security;
alter table partners.data_subject_requests force row level security;
create policy tenant_isolation on partners.data_subject_requests
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on partners.data_subject_requests to ${application_role};
