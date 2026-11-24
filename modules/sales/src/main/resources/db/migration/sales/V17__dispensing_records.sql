-- STD-7: the dispensing record of a sale with PRESCRIPTION or CONTROLLED lines. The prescription's
-- date (required for such a sale from now on, with the prescriber's registration number and the
-- patient's name), and who dispensed it: the seller, who must hold sales.dispense_rx. Nullable
-- columns without defaults: existing sales keep null (they were sold before the rule), and no row
-- is rewritten.
alter table sales.sales
  add column prescription_date date,
  add column dispensed_by     text,
  add column dispensed_by_uid uuid;
