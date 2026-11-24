-- STD-2 (adversarial review 23 Nov 2026): the input VAT on a supplier's invoice.
--   vat_total       the invoice's VAT, in the receipt's currency, on top of the lines' costs (which
--                   exclude it): total = the lines + vat_total, what the supplier is owed.
--   base_vat_total  vat_total in the base currency, rounded once; base_total = the lines' base
--                   value + base_vat_total, so the stock's value is what it is without VAT.
-- The ledger posts it to 1150 VAT receivable (Dr 1200 lines, Dr 1150 VAT / Cr 2000 total). Only
-- a VAT-registered pharmacy records it. Existing receipts had none: constant defaults of 0, so the
-- table is not rewritten and no row is changed.
alter table purchasing.goods_receipts
  add column vat_total numeric(14,2) not null default 0 check (vat_total >= 0),
  add column base_vat_total numeric(14,2) not null default 0 check (base_vat_total >= 0);
