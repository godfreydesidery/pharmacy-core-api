-- PERF-1: the fiscal receipt is issued after the sale commits, by the sale.completed reaction,
-- never inside the transaction that holds the branch's sale number and the stock.
--   fiscal_due            the receipt is still to be issued (a fiscal integration is on)
--   fiscal_claimed_until  the lease of the attempt under way: another attempt waits until it ends
-- Constant defaults only: PostgreSQL records them in the catalogue, the table is not rewritten.
alter table sales.sales
  add column fiscal_due boolean not null default false,
  add column fiscal_claimed_until timestamptz;
