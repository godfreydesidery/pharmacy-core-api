-- insurance: payers, schemes, members, claims and their lines, claim batches and remittances.
-- Every table carries tenant_uid with forced row-level security.

grant usage on schema insurance to ${application_role};

create table insurance.payers (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  tenant_uid         uuid not null,
  code               text not null,
  name               text not null,
  contact            text,
  email              text,
  payment_terms_days integer not null default 30,
  requires_preauth   boolean not null default false,
  active             boolean not null default true,
  created_at         timestamptz not null,
  created_by         text not null,
  updated_at         timestamptz,
  updated_by         text,
  version            bigint not null default 0,
  unique (tenant_uid, code)
);

create table insurance.schemes (
  id                     bigint generated always as identity primary key,
  uid                    uuid not null unique,
  tenant_uid             uuid not null,
  payer_uid              uuid not null,
  name                   text not null,
  copay_type             text not null check (copay_type in ('NONE', 'PERCENT', 'FIXED')),
  copay_value            numeric(14, 2) not null default 0 check (copay_value >= 0),
  annual_limit           numeric(14, 2) check (annual_limit is null or annual_limit >= 0),
  excluded_product_types text not null default '',
  excluded_product_uids  text not null default '',
  active                 boolean not null default true,
  created_at             timestamptz not null,
  created_by             text not null,
  updated_at             timestamptz,
  updated_by             text,
  version                bigint not null default 0
);
create index schemes_by_payer on insurance.schemes (tenant_uid, payer_uid);

create table insurance.members (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  customer_uid   uuid not null,
  customer_name  text not null,
  scheme_uid     uuid not null,
  member_number  text not null,
  principal_name text,
  valid_from     date,
  valid_until    date,
  active         boolean not null default true,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0
);
create unique index members_number_per_scheme on insurance.members (tenant_uid, scheme_uid, lower(member_number));
create index members_by_customer on insurance.members (tenant_uid, customer_uid);

create table insurance.claims (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  number         text not null,
  payer_uid      uuid not null,
  member_uid     uuid not null,
  customer_uid   uuid,
  member_number  text,
  patient_name   text,
  sale_uid       uuid,
  sale_number    text,
  preauth_number text,
  diagnosis_code text,
  claimed        numeric(14, 2) not null default 0,
  approved       numeric(14, 2),
  paid           numeric(14, 2) not null default 0,
  status         text not null check (status in ('DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIALLY_APPROVED', 'REJECTED', 'PAID', 'VOIDED')),
  batch_uid      uuid,
  note           text,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0,
  unique (tenant_uid, number)
);
create index claims_by_payer_status on insurance.claims (tenant_uid, payer_uid, status);
create index claims_by_member on insurance.claims (tenant_uid, member_uid, created_at);
create index claims_by_sale on insurance.claims (tenant_uid, sale_uid);
create index claims_by_batch on insurance.claims (tenant_uid, batch_uid);

create table insurance.claim_lines (
  id               bigint generated always as identity primary key,
  uid              uuid not null unique,
  tenant_uid       uuid not null,
  claim_id         bigint not null references insurance.claims (id),
  product_uid      uuid,
  product_name     text,
  quantity         integer not null default 0,
  amount           numeric(14, 2) not null,
  approved_amount  numeric(14, 2),
  rejection_reason text,
  created_at       timestamptz not null,
  created_by       text not null,
  updated_at       timestamptz,
  updated_by       text,
  version          bigint not null default 0
);
create index claim_lines_by_claim on insurance.claim_lines (claim_id);

create table insurance.claim_batches (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  tenant_uid   uuid not null,
  number       text not null,
  payer_uid    uuid not null,
  period_from  date not null,
  period_to    date not null,
  status       text not null,
  submitted_at timestamptz,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0,
  unique (tenant_uid, number)
);

create table insurance.remittances (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  number      text not null,
  payer_uid   uuid not null,
  amount      numeric(14, 2) not null check (amount > 0),
  reference   text,
  remitted_on date not null,
  allocations text not null default '',
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0,
  unique (tenant_uid, number)
);
create trigger remittances_are_append_only
  before update or delete on insurance.remittances
  for each row execute function platform.refuse_change();

do $$
declare
  t text;
begin
  foreach t in array array['payers', 'schemes', 'members', 'claims', 'claim_lines', 'claim_batches', 'remittances'] loop
    execute format('alter table insurance.%I enable row level security', t);
    execute format('alter table insurance.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on insurance.%I using (tenant_uid = platform.current_tenant_uid())'
      || ' with check (tenant_uid = platform.current_tenant_uid())', t);
  end loop;
end
$$;

grant select, insert, update on insurance.payers, insurance.schemes, insurance.members,
  insurance.claims, insurance.claim_lines, insurance.claim_batches to ${application_role};
grant select, insert on insurance.remittances to ${application_role};
