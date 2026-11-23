-- Stock per product and branch (adversarial review PERF-8). The stock list and the stock summary
-- added up every stock level and batch of the branch for each page, twice. inventory.product_stock
-- keeps, for each pharmacy, branch and product, what they read: the units on hand, their value at
-- cost, the nearest expiry and the number of batches in stock.
--
-- It is kept by triggers, in the same transaction as the change, whichever code path or release
-- makes it:
--   - a stock level inserted, or its quantity changed (every movement changes one);
--   - a batch's unit cost (cost averaging) or expiry changed, for each branch holding it.
-- Each recomputes the one (branch, product) row from its stock levels (a product has a few batches
-- at a branch). It first locks the row, then reads the levels in a new statement: under READ
-- COMMITTED that read sees every change committed by a transaction that held the row before, so
-- two tills selling the same product never leave a stale row (no lost update). Levels are never
-- deleted except by a pharmacy's erasure, which deletes this table too.
--
-- The backfill. The SHARE ROW EXCLUSIVE locks on stock_levels and batches (taken explicitly, then
-- by creating the triggers) let running stock changes finish first (or the 5 s lock timeout fails
-- the migration, which is then run again) and hold new ones until the commit: nothing changes
-- between the backfill's read and the triggers. Each pharmacy in turn, under its own row-level
-- security; rows are only inserted, into the new table. Nothing existing is changed.

create table inventory.product_stock (
  tenant_uid     uuid not null,
  branch_uid     uuid not null,
  product_uid    uuid not null,
  quantity       bigint not null default 0,
  cost_value     numeric not null default 0,
  nearest_expiry date,
  batch_count    integer not null default 0,
  primary key (tenant_uid, branch_uid, product_uid)
);
alter table inventory.product_stock enable row level security;
alter table inventory.product_stock force row level security;
create policy tenant_isolation on inventory.product_stock
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on inventory.product_stock to ${application_role};

-- One product's row at one branch, recomputed from its stock levels after locking it. Runs as the
-- caller (the application role), under its row-level security.
create function inventory.refresh_product_stock(p_tenant uuid, p_branch uuid, p_product uuid)
  returns void
  language plpgsql
  set search_path = pg_catalog, pg_temp
as $$
begin
  insert into inventory.product_stock (tenant_uid, branch_uid, product_uid)
  values (p_tenant, p_branch, p_product)
  on conflict (tenant_uid, branch_uid, product_uid) do nothing;
  perform 1 from inventory.product_stock s
   where s.tenant_uid = p_tenant and s.branch_uid = p_branch and s.product_uid = p_product
     for update;
  update inventory.product_stock s
     set quantity = f.quantity, cost_value = f.cost_value,
         nearest_expiry = f.nearest_expiry, batch_count = f.batch_count
    from (select coalesce(sum(sl.quantity), 0) as quantity,
                 coalesce(sum(sl.quantity * b.unit_cost), 0) as cost_value,
                 min(b.expiry_date) filter (where sl.quantity > 0) as nearest_expiry,
                 count(*) filter (where sl.quantity > 0) as batch_count
            from inventory.stock_levels sl
            join inventory.batches b on b.id = sl.batch_id
           where sl.tenant_uid = p_tenant and sl.branch_uid = p_branch
             and sl.product_uid = p_product) f
   where s.tenant_uid = p_tenant and s.branch_uid = p_branch and s.product_uid = p_product;
end
$$;

create function inventory.stock_level_changed() returns trigger
  language plpgsql
  set search_path = pg_catalog, pg_temp
as $$
begin
  perform inventory.refresh_product_stock(new.tenant_uid, new.branch_uid, new.product_uid);
  return null;
end
$$;

create function inventory.batch_value_changed() returns trigger
  language plpgsql
  set search_path = pg_catalog, pg_temp
as $$
declare
  v_branch uuid;
begin
  for v_branch in
    select distinct sl.branch_uid from inventory.stock_levels sl
     where sl.batch_id = new.id order by sl.branch_uid
  loop
    perform inventory.refresh_product_stock(new.tenant_uid, v_branch, new.product_uid);
  end loop;
  return null;
end
$$;

lock table inventory.stock_levels, inventory.batches in share row exclusive mode;

create trigger stock_levels_inserted_product_stock
  after insert on inventory.stock_levels
  for each row execute function inventory.stock_level_changed();
create trigger stock_levels_changed_product_stock
  after update of quantity on inventory.stock_levels
  for each row when (old.quantity is distinct from new.quantity)
  execute function inventory.stock_level_changed();
create trigger batches_changed_product_stock
  after update of unit_cost, expiry_date on inventory.batches
  for each row
  when (old.unit_cost is distinct from new.unit_cost or old.expiry_date is distinct from new.expiry_date)
  execute function inventory.batch_value_changed();

do $$
declare
  t uuid;
begin
  for t in select uid from tenancy.tenants loop
    perform set_config('pharmacy.tenant_uid', t::text, true);
    insert into inventory.product_stock
      (tenant_uid, branch_uid, product_uid, quantity, cost_value, nearest_expiry, batch_count)
    select sl.tenant_uid, sl.branch_uid, sl.product_uid,
           sum(sl.quantity), sum(sl.quantity * b.unit_cost),
           min(b.expiry_date) filter (where sl.quantity > 0),
           count(*) filter (where sl.quantity > 0)
      from inventory.stock_levels sl
      join inventory.batches b on b.id = sl.batch_id
     group by sl.tenant_uid, sl.branch_uid, sl.product_uid;
  end loop;
  perform set_config('pharmacy.tenant_uid', '', true);
end
$$;
