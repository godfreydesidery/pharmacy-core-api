-- Pricing plans (the price lists, named so in the app). A plan's rule for a medicine it has no
-- price for is now optional: no rule (null) blocks the sale of that medicine under the plan; 0 sells
-- it at the Normal Sale price; any other percentage adjusts the Normal Sale price. Existing lists
-- keep the rule they had, so nothing changes for them.
alter table catalogue.price_lists alter column adjustment_percent drop not null;
alter table catalogue.price_lists alter column adjustment_percent drop default;

-- An insurer's plan: priced by the insurer's tariff, taken by default for its members' sales.
alter table catalogue.price_lists
  add column payer_uid  uuid,
  add column payer_name text;

-- Price history: every change of a medicine's Normal Sale price or of its price in a plan, kept
-- for good (append-only). price_list_uid null means Normal Sale.
create table catalogue.price_changes (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  product_uid    uuid not null,
  product_name   text not null,
  price_list_uid uuid,
  plan_name      text not null,
  min_quantity   integer not null default 1,
  old_price      numeric(14,2),
  new_price      numeric(14,2),
  source         text not null check (source in ('MANUAL', 'IMPORT', 'PRODUCT', 'BULK')),
  created_at     timestamptz not null,
  created_by     text not null,
  created_by_uid uuid,
  updated_at     timestamptz,
  updated_by     text,
  updated_by_uid uuid,
  version        bigint not null default 0
);
create index price_changes_by_product on catalogue.price_changes (tenant_uid, product_uid, created_at desc);
alter table catalogue.price_changes enable row level security;
alter table catalogue.price_changes force row level security;
create policy tenant_isolation on catalogue.price_changes
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
create trigger price_changes_are_append_only
  before update or delete on catalogue.price_changes
  for each row execute function platform.refuse_change();
grant select, insert on catalogue.price_changes to ${application_role};
