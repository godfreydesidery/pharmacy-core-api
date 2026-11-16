-- Packaging: a pack product broken into a unit product (a box of 10 strips: the strip is sold, the
-- box is bought), and the formula of a compounded preparation (ingredients per unit made).

create table catalogue.pack_links (
  id                bigint generated always as identity primary key,
  uid               uuid not null unique,
  tenant_uid        uuid not null,
  pack_product_uid  uuid not null,
  unit_product_uid  uuid not null,
  units_per_pack    integer not null check (units_per_pack >= 2),
  created_at        timestamptz not null,
  created_by        text not null,
  updated_at        timestamptz,
  updated_by        text,
  version           bigint not null default 0,
  check (pack_product_uid <> unit_product_uid)
);
create unique index pack_links_by_unit on catalogue.pack_links (tenant_uid, unit_product_uid);
alter table catalogue.pack_links enable row level security;
alter table catalogue.pack_links force row level security;
create policy tenant_isolation on catalogue.pack_links
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on catalogue.pack_links to ${application_role};

create table catalogue.formula_items (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  product_uid     uuid not null,
  ingredient_uid  uuid not null,
  quantity        numeric(12,3) not null check (quantity > 0),
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0,
  check (product_uid <> ingredient_uid)
);
create unique index formula_items_unique on catalogue.formula_items (tenant_uid, product_uid, ingredient_uid);
alter table catalogue.formula_items enable row level security;
alter table catalogue.formula_items force row level security;
create policy tenant_isolation on catalogue.formula_items
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on catalogue.formula_items to ${application_role};
