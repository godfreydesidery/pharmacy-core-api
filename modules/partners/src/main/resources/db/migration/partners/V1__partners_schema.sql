-- partners: customers and suppliers, per tenant, each with row-level security.

grant usage on schema partners to ${application_role};

create table partners.customers (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  tenant_uid         uuid not null,
  code               text not null,
  name               text not null,
  phone              text,
  email              text,
  type               text not null check (type in ('RETAIL', 'WHOLESALE', 'INSURANCE')),
  insurance_provider text,
  insurance_number   text,
  credit_limit       numeric(14, 2) not null default 0 check (credit_limit >= 0),
  address            text,
  active             boolean not null default true,
  created_at         timestamptz not null,
  created_by         text not null,
  updated_at         timestamptz,
  updated_by         text,
  version            bigint not null default 0,
  unique (tenant_uid, code)
);
create index customers_by_name on partners.customers (tenant_uid, lower(name));
create index customers_by_phone on partners.customers (tenant_uid, phone);
alter table partners.customers enable row level security;
alter table partners.customers force row level security;
create policy tenant_isolation on partners.customers
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on partners.customers to ${application_role};

create table partners.suppliers (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  code           text not null,
  name           text not null,
  contact_person text,
  phone          text,
  email          text,
  tin            text,
  payment_terms  text not null check (payment_terms in ('COD', 'NET_15', 'NET_30', 'NET_45', 'NET_60')),
  address        text,
  active         boolean not null default true,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0,
  unique (tenant_uid, code)
);
create index suppliers_by_name on partners.suppliers (tenant_uid, lower(name));
alter table partners.suppliers enable row level security;
alter table partners.suppliers force row level security;
create policy tenant_isolation on partners.suppliers
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on partners.suppliers to ${application_role};
