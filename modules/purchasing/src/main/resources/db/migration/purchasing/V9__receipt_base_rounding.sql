-- STD-17 (adversarial review, Batch 3 "still open"): a foreign-currency receipt's base values.
--   base_total     what is owed in base: the receipt's total converted once, half-up to the base
--                  currency's minor units (as checkout converts a foreign tender), so paying the
--                  whole total at the booked rate settles it to the last minor unit.
--   base_rounding  base_total less the base value of its lines (each line converted once, the
--                  value the stock is received at) and of its VAT: the conversion's rounding, a
--                  few minor units either way, posted to 7000 so inventory equals the stock at
--                  cost and the payable what is owed.
-- A base-currency receipt has none. Existing receipts keep theirs (0, a constant default: no
-- rewrite, no row changed).
alter table purchasing.goods_receipts
  add column base_rounding numeric(14,2) not null default 0;
