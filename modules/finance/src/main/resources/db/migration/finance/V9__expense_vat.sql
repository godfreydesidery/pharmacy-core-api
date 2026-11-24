-- STD-2 (adversarial review 23 Nov 2026): the input VAT in an expense paid with a VAT invoice.
--   vat_amount  the VAT in amount (amount is what was paid), booked to 1150 VAT receivable: Dr the
--               expense account (amount - VAT) and 1150 (VAT) / Cr the money account (amount). Only
--               a VAT-registered pharmacy records it.
-- The account 1150 VAT Receivable joins each pharmacy's chart on its next posting (ChartOfAccounts
-- seeds missing default accounts). Existing expenses had no VAT: a constant default of 0, so the
-- table is not rewritten and no row is changed.
alter table finance.expenses
  add column vat_amount numeric(14,2) not null default 0 check (vat_amount >= 0);
