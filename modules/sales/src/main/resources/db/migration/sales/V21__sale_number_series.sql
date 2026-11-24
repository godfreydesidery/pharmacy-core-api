-- STD-4: a branch's invoice numbers are one series whatever the invoice prefix. Until now the
-- counter was keyed by the prefix ("INV-HQ"), so a new prefix started again at 1. The counter is
-- now "sale:<branch code>"; each branch's starts after the highest number its sales already carry
-- under any prefix, so the next number never repeats or goes back, and carries on from there.
--
-- Sales are never deleted, so the highest number on a sale is the series' true top. A number is
-- <prefix>-<branch code>-<digits> (prefix and code are 2 to 12 letters or digits, never a dash).
--
-- Migrations run as the tables' owner, whom forced row-level security would hold to no tenant: it
-- is lifted while the counters are read and written, and restored.

alter table sales.sales no force row level security;
alter table platform.document_sequences no force row level security;

insert into platform.document_sequences (tenant_uid, sequence_key, last_value)
select tenant_uid, 'sale:' || parts[1], max(parts[2]::bigint)
  from (select tenant_uid,
               regexp_match(number, '^[A-Za-z0-9]+-([A-Za-z0-9]+)-([0-9]{1,18})$') as parts
          from sales.sales) numbered
 where parts is not null
 group by tenant_uid, parts[1]
on conflict (tenant_uid, sequence_key)
  do update set last_value = greatest(platform.document_sequences.last_value, excluded.last_value);

alter table platform.document_sequences force row level security;
alter table sales.sales force row level security;
