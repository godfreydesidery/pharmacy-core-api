-- Customer statements (adversarial review PERF-17) read a page of one period's entries, and the
-- balance brought forward from receivables.customer_daily_balances instead of adding up every entry
-- the customer ever had: for each pharmacy, customer and entry date, the sum of the entries' signed
-- amounts (debits positive, credits negative).
--
-- A trigger keeps it in the same transaction as each entry, with one atomic upsert, whichever code
-- path or release writes it. An entry's amount and date never change (only open_amount moves as
-- allocations settle it); if one ever did, the trigger moves the amount from the old day to the new.
--
-- The backfill. Creating the triggers takes a SHARE ROW EXCLUSIVE lock on receivables.entries
-- until the commit: running writes finish first (or the 5 s lock timeout fails the migration, which
-- is then run again), and new ones wait, so nothing is missed or counted twice. Each pharmacy in
-- turn, under its own row-level security; rows are only inserted, into the new table.

create table receivables.customer_daily_balances (
  tenant_uid   uuid not null,
  customer_uid uuid not null,
  day          date not null,
  amount       numeric(18,2) not null default 0,
  primary key (tenant_uid, customer_uid, day)
);
alter table receivables.customer_daily_balances enable row level security;
alter table receivables.customer_daily_balances force row level security;
create policy tenant_isolation on receivables.customer_daily_balances
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on receivables.customer_daily_balances to ${application_role};

-- Runs as the writing role (the application's), under its row-level security.
create function receivables.entry_to_daily_balance() returns trigger
  language plpgsql
  set search_path = pg_catalog, pg_temp
as $$
begin
  if tg_op = 'UPDATE' then
    insert into receivables.customer_daily_balances as d (tenant_uid, customer_uid, day, amount)
    values (old.tenant_uid, old.customer_uid, old.entry_date, -old.amount)
    on conflict (tenant_uid, customer_uid, day) do update set amount = d.amount + excluded.amount;
  end if;
  insert into receivables.customer_daily_balances as d (tenant_uid, customer_uid, day, amount)
  values (new.tenant_uid, new.customer_uid, new.entry_date, new.amount)
  on conflict (tenant_uid, customer_uid, day) do update set amount = d.amount + excluded.amount;
  return null;
end
$$;

create trigger entries_inserted_daily_balance
  after insert on receivables.entries
  for each row execute function receivables.entry_to_daily_balance();
create trigger entries_moved_daily_balance
  after update of amount, entry_date, customer_uid on receivables.entries
  for each row
  when (old.amount is distinct from new.amount or old.entry_date is distinct from new.entry_date
        or old.customer_uid is distinct from new.customer_uid)
  execute function receivables.entry_to_daily_balance();

do $$
declare
  t uuid;
begin
  for t in select uid from tenancy.tenants loop
    perform set_config('pharmacy.tenant_uid', t::text, true);
    insert into receivables.customer_daily_balances (tenant_uid, customer_uid, day, amount)
    select e.tenant_uid, e.customer_uid, e.entry_date, sum(e.amount)
      from receivables.entries e
     group by e.tenant_uid, e.customer_uid, e.entry_date;
  end loop;
  perform set_config('pharmacy.tenant_uid', '', true);
end
$$;
