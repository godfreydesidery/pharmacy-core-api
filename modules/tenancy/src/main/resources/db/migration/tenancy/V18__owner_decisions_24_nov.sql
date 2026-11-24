-- The owner's decisions of 24 Nov 2026 (adversarial review, Batch 10 stream 5), per pharmacy:
-- shared_counter_pcs (WEB-5): the till PCs are shared, so a session ends when the browser closes and
--   parked sales expire at the end of the pharmacy's business day (instead of 12 hours). Off.
-- expiry_provision_enabled, expiry_provision_bands (STD-3): the monthly net-realisable-value
--   write-down of stock near or past expiry. Bands are "days:percent" pairs joined by ';', days
--   ascending: stock already expired at month end (0), expiring within 30 days, within 90 days.
--   On, with expired 100%, within 30 days 50%, within 90 days 25%. The pharmacy's accountant
--   confirms the method; the first run is for the month after this release.
-- vat_apportionment_method, vat_fixed_recoverable_percent, vat_year_end_month, vat_ratio_round_up
--   (STD-2): how input VAT that serves both taxable and exempt sales is split. AUTOMATIC (the
--   taxable share of sales, a provisional monthly ratio and a true-up in the year-end month) or
--   FIXED (a percentage agreed with TRA or the accountant). December year end; the automatic ratio
--   is rounded up to the next whole percent.
-- Columns with constant defaults: existing rows take them, the table is not rewritten and nothing
-- is updated.
alter table tenancy.settings
  add column shared_counter_pcs boolean not null default false,
  add column expiry_provision_enabled boolean not null default true,
  add column expiry_provision_bands text not null default '0:100;30:50;90:25'
    check (expiry_provision_bands ~ '^[0-9]{1,3}:[0-9]{1,3}(\.[0-9]{1,2})?(;[0-9]{1,3}:[0-9]{1,3}(\.[0-9]{1,2})?){0,4}$'),
  add column vat_apportionment_method text not null default 'AUTOMATIC'
    check (vat_apportionment_method in ('AUTOMATIC', 'FIXED')),
  add column vat_fixed_recoverable_percent numeric(5,2)
    check (vat_fixed_recoverable_percent is null
           or vat_fixed_recoverable_percent between 0 and 100),
  add column vat_year_end_month integer not null default 12
    check (vat_year_end_month between 1 and 12),
  add column vat_ratio_round_up boolean not null default true,
  add constraint settings_fixed_method_has_percent
    check (vat_apportionment_method <> 'FIXED' or vat_fixed_recoverable_percent is not null);
