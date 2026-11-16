-- Pricing: price lists (for a customer type, or one customer's special prices) with quantity
-- breaks, branch prices and promotions. The till resolves a line's price in this order: branch
-- price or retail; then the customer's own list, else their type's list (the item with the highest
-- minimum quantity reached, else the list's percentage on the base price); then the best promotion.

create table catalogue.price_lists (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  name                text not null,
  customer_type       text,
  customer_uid        uuid,
  customer_name       text,
  adjustment_percent  numeric(7,3) not null default 0 check (adjustment_percent > -100),
  valid_from          date,
  valid_to            date,
  active              boolean not null default true,
  check (customer_type is null or customer_uid is null),
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0
);
create unique index price_lists_unique_name on catalogue.price_lists (tenant_uid, lower(name));
alter table catalogue.price_lists enable row level security;
alter table catalogue.price_lists force row level security;
create policy tenant_isolation on catalogue.price_lists
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on catalogue.price_lists to ${application_role};

create table catalogue.price_list_items (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  price_list_id  bigint not null references catalogue.price_lists (id) on delete cascade,
  product_uid    uuid not null,
  min_quantity   integer not null default 1 check (min_quantity >= 1),
  price          numeric(14,2) not null check (price >= 0),
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0
);
create unique index price_list_items_unique on catalogue.price_list_items (price_list_id, product_uid, min_quantity);
alter table catalogue.price_list_items enable row level security;
alter table catalogue.price_list_items force row level security;
create policy tenant_isolation on catalogue.price_list_items
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on catalogue.price_list_items to ${application_role};

create table catalogue.branch_prices (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  product_uid  uuid not null,
  branch_uid   uuid not null,
  price        numeric(14,2) not null check (price >= 0),
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0
);
create unique index branch_prices_unique on catalogue.branch_prices (tenant_uid, product_uid, branch_uid);
alter table catalogue.branch_prices enable row level security;
alter table catalogue.branch_prices force row level security;
create policy tenant_isolation on catalogue.branch_prices
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on catalogue.branch_prices to ${application_role};

create table catalogue.promotions (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  name          text not null,
  percent_off   numeric(5,2) not null check (percent_off > 0 and percent_off <= 100),
  product_uid   uuid,
  category_uid  uuid,
  branch_uid    uuid,
  valid_from    date not null,
  valid_to      date not null,
  active        boolean not null default true,
  check (valid_to >= valid_from),
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0
);
alter table catalogue.promotions enable row level security;
alter table catalogue.promotions force row level security;
create policy tenant_isolation on catalogue.promotions
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on catalogue.promotions to ${application_role};
