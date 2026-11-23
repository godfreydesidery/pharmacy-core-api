-- Daily account balances (adversarial review PERF-4) and the journal's own number series (PERF-14).
--
-- 1. finance.account_daily_balances: for each pharmacy, account, booking day and branch (null for
--    the pharmacy's own entries, such as the opening balances), the sum of the journal's debits and
--    credits. A trigger on finance.journal_lines adds each line to its row in the same transaction,
--    with one atomic upsert (insert ... on conflict do update), whichever instance, code path or
--    release inserts the line. Lines are append-only, so the read model only ever grows by inserts.
--    The day is the entry's booking date: a backdated posting, or one the period lock moved to the
--    first open day, updates that day's row. Balances, the trial balance, the balance sheet and VAT
--    read this table instead of adding up the journal (GET /finance/reconciliation/balances checks
--    it against the journal).
--
-- 2. The backfill. Creating the trigger takes a SHARE ROW EXCLUSIVE lock on journal_lines until
--    this migration commits: postings that are running finish first (or the 5 s lock timeout fails
--    the migration, which is then run again), and new ones wait until the end. So every line
--    committed before is in the backfill, and every line after goes through the trigger: none is
--    missed and none counted twice. Each pharmacy in turn, under its own row-level security; rows
--    are only inserted, into the new table. Nothing existing is changed.
--
-- 3. finance.journal_counters: the journal voucher numbers, JV-<year>-<number>, one series per
--    pharmacy and booking year. The next number is one atomic upsert ... returning on the series'
--    row, taken by the journal writer as the last step before the entry is inserted, so the row is
--    locked only from there to the commit. It is a counter row, not a PostgreSQL sequence: a
--    posting that rolls back gives its number back, so the series has no gaps (auditors read a gap
--    in journal vouchers as a deleted entry). Numbers already given (JV-000123) stay as they are.

create table finance.account_daily_balances (
  id           bigint generated always as identity primary key,
  tenant_uid   uuid not null,
  account_code text not null,
  day          date not null,
  branch_uid   uuid,
  debit        numeric(18,2) not null default 0,
  credit       numeric(18,2) not null default 0,
  constraint account_daily_balances_key
    unique nulls not distinct (tenant_uid, account_code, day, branch_uid)
);
alter table finance.account_daily_balances enable row level security;
alter table finance.account_daily_balances force row level security;
create policy tenant_isolation on finance.account_daily_balances
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on finance.account_daily_balances to ${application_role};

-- Runs as the inserting role (the application's), under its row-level security.
create function finance.add_line_to_daily_balance() returns trigger
  language plpgsql
  set search_path = pg_catalog, pg_temp
as $$
declare
  v_day    date;
  v_branch uuid;
begin
  select e.entry_date, e.branch_uid into v_day, v_branch
    from finance.journal_entries e
   where e.id = new.entry_id;
  insert into finance.account_daily_balances as b
    (tenant_uid, account_code, day, branch_uid, debit, credit)
  values (new.tenant_uid, new.account_code, v_day, v_branch, new.debit, new.credit)
  on conflict on constraint account_daily_balances_key
  do update set debit = b.debit + excluded.debit, credit = b.credit + excluded.credit;
  return null;
end
$$;

create trigger journal_lines_add_to_daily_balances
  after insert on finance.journal_lines
  for each row execute function finance.add_line_to_daily_balance();

do $$
declare
  t uuid;
begin
  for t in select uid from tenancy.tenants loop
    perform set_config('pharmacy.tenant_uid', t::text, true);
    insert into finance.account_daily_balances
      (tenant_uid, account_code, day, branch_uid, debit, credit)
    select l.tenant_uid, l.account_code, e.entry_date, e.branch_uid, sum(l.debit), sum(l.credit)
      from finance.journal_lines l
      join finance.journal_entries e on e.id = l.entry_id
     group by l.tenant_uid, l.account_code, e.entry_date, e.branch_uid;
  end loop;
  perform set_config('pharmacy.tenant_uid', '', true);
end
$$;

create table finance.journal_counters (
  tenant_uid uuid not null,
  year       integer not null,
  last_value bigint not null,
  primary key (tenant_uid, year)
);
alter table finance.journal_counters enable row level security;
alter table finance.journal_counters force row level security;
create policy tenant_isolation on finance.journal_counters
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on finance.journal_counters to ${application_role};
