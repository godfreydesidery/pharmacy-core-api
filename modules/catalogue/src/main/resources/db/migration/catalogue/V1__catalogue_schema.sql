-- catalogue: categories, manufacturers and products (medicines), per tenant.
--
-- Every table carries tenant_uid and a row-level security policy: a query sees the current
-- tenant's rows only, whatever it forgets to filter.

grant usage on schema catalogue to ${application_role};

create table catalogue.categories (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  name        text not null,
  description text,
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0
);
create unique index categories_unique_name on catalogue.categories (tenant_uid, lower(name));
alter table catalogue.categories enable row level security;
alter table catalogue.categories force row level security;
create policy tenant_isolation on catalogue.categories
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on catalogue.categories to ${application_role};

create table catalogue.manufacturers (
  id         bigint generated always as identity primary key,
  uid        uuid not null unique,
  tenant_uid uuid not null,
  name       text not null,
  country    text,
  created_at timestamptz not null,
  created_by text not null,
  updated_at timestamptz,
  updated_by text,
  version    bigint not null default 0
);
create unique index manufacturers_unique_name
  on catalogue.manufacturers (tenant_uid, lower(name));
alter table catalogue.manufacturers enable row level security;
alter table catalogue.manufacturers force row level security;
create policy tenant_isolation on catalogue.manufacturers
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on catalogue.manufacturers to ${application_role};

-- A product: generic name per WHO INN, ATC code, GS1 GTIN, schedule (OTC / prescription /
-- controlled) and storage condition (good storage practice).
create table catalogue.products (
  id               bigint generated always as identity primary key,
  uid              uuid not null unique,
  tenant_uid       uuid not null,
  name             text not null,
  generic_name     text,
  sku              text,
  gtin             text,
  atc_code         text,
  category_id      bigint references catalogue.categories (id),
  manufacturer_id  bigint references catalogue.manufacturers (id),
  dosage_form      text not null,
  strength         text,
  pack_size        text,
  schedule         text not null check (schedule in ('OTC', 'PRESCRIPTION', 'CONTROLLED')),
  selling_price    numeric(14, 2) not null check (selling_price >= 0),
  cost_price       numeric(14, 2) not null check (cost_price >= 0),
  tax_rate_percent numeric(5, 2) not null default 0
                     check (tax_rate_percent between 0 and 100),
  reorder_level    integer not null default 0 check (reorder_level >= 0),
  storage          text not null,
  active           boolean not null default true,
  created_at       timestamptz not null,
  created_by       text not null,
  updated_at       timestamptz,
  updated_by       text,
  version          bigint not null default 0
);
create unique index products_unique_sku
  on catalogue.products (tenant_uid, lower(sku)) where sku is not null;
create unique index products_unique_gtin
  on catalogue.products (tenant_uid, gtin) where gtin is not null;
create index products_by_name on catalogue.products (tenant_uid, lower(name));
create index products_by_category on catalogue.products (category_id);
alter table catalogue.products enable row level security;
alter table catalogue.products force row level security;
create policy tenant_isolation on catalogue.products
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on catalogue.products to ${application_role};
