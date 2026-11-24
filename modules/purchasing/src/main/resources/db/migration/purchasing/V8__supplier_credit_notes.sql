-- Supplier returns wait for the supplier's credit note (adversarial review FRD-16) and, once
-- credited, lower what is still open on the goods receipt they came on (Batch 3, "still open").
--
--   status              PENDING_CREDIT: the stock is out and the debit note sent; nothing is owed
--                       less yet (the ledger holds the value in 1160 "Supplier returns awaiting
--                       credit"). CREDITED: the supplier's credit note is recorded and lowers what
--                       is owed (Dr 2000 / Cr 1160, any difference to 5100).
--   credit_note_*       the supplier's credit note: its number and date.
--   credit_currency     the credit's currency: the goods receipt's when the return names one, else
--                       the base currency. credit_amount is in it.
--   credit_base_amount  credit_amount in the base currency, at the goods receipt's booked rate (so
--                       a foreign payable comes down at the rate it went up at).
--   credit_applied      how much of the credit lowered the goods receipt's open amount, in the
--                       receipt's currency: at most what was still open on it (the rest is credit
--                       on the supplier's account). 0 for a return without a receipt.
--
-- Returns recorded before this migration were booked against what is owed when they were made
-- (Dr 2000 / Cr 1200): they are CREDITED (the constant default fills them without a rewrite) with
-- no credit note, and count at their total. New returns start PENDING_CREDIT.
alter table purchasing.supplier_returns
  add column status text not null default 'CREDITED'
    check (status in ('PENDING_CREDIT', 'CREDITED')),
  add column credit_note_number text,
  add column credit_note_date date,
  add column credit_currency text check (credit_currency ~ '^[A-Z]{3}$'),
  add column credit_amount numeric(14,2) check (credit_amount > 0),
  add column credit_base_amount numeric(14,2) check (credit_base_amount >= 0),
  add column credit_applied numeric(14,2) not null default 0 check (credit_applied >= 0),
  add column credited_at timestamptz,
  add column credited_by text,
  add column credited_by_uid uuid;
alter table purchasing.supplier_returns alter column status set default 'PENDING_CREDIT';

-- One credit note number per supplier (the same note recorded twice would lower the debt twice).
-- The table holds a few rows per supplier and month, so a plain build is short.
create unique index supplier_returns_credit_note
  on purchasing.supplier_returns (tenant_uid, supplier_uid, lower(credit_note_number))
  where credit_note_number is not null;
-- The credited returns of a goods receipt (its open amount) and the pending ones of a branch.
create index supplier_returns_by_receipt
  on purchasing.supplier_returns (tenant_uid, goods_receipt_uid)
  where goods_receipt_uid is not null;

-- Recording the credit note changes the return's row.
grant update on purchasing.supplier_returns to ${application_role};

-- Earlier returns against a goods receipt lower its open amount by their value in the receipt's
-- currency (the batch cost in base at the receipt's rate, to the currency's minor units), oldest
-- first and never below zero: what payments have already settled of the receipt stays settled,
-- and any rest is credit on the supplier's account (as their balance already shows). Every
-- pharmacy in turn, under its own row-level security (its payments' allocations are read in it).
do $$
declare
  t      uuid;
  r      record;
  open   numeric(14,2);
  value  numeric(14,2);
begin
  -- A new database (finance migrates after purchasing) has no earlier returns to backfill.
  if to_regclass('finance.supplier_payment_allocations') is null then
    return;
  end if;
  for t in select uid from tenancy.tenants loop
    perform set_config('pharmacy.tenant_uid', t::text, true);
    for r in
      select s.id, s.total, g.uid as receipt_uid, g.total as receipt_total, g.currency,
             g.exchange_rate
        from purchasing.supplier_returns s
        join purchasing.goods_receipts g on g.uid = s.goods_receipt_uid
       where s.status = 'CREDITED' and s.credit_note_number is null
       order by s.id
    loop
      select r.receipt_total
             - coalesce((select sum(a.amount) from finance.supplier_payment_allocations a
                          where a.goods_receipt_uid = r.receipt_uid), 0)
             - coalesce((select sum(o.credit_applied) from purchasing.supplier_returns o
                          where o.goods_receipt_uid = r.receipt_uid and o.id <> r.id), 0)
        into open;
      value := round(r.total / r.exchange_rate,
                     case when r.currency in ('BIF', 'CLP', 'DJF', 'GNF', 'ISK', 'JPY', 'KMF',
                                              'KRW', 'PYG', 'RWF', 'UGX', 'VND', 'VUV', 'XAF',
                                              'XOF', 'XPF')
                          then 0 else 2 end);
      if least(value, open) > 0 then
        update purchasing.supplier_returns set credit_applied = least(value, open)
         where id = r.id;
      end if;
    end loop;
  end loop;
  perform set_config('pharmacy.tenant_uid', '', true);
end
$$;
