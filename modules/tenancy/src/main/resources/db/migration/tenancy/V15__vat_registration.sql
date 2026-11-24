-- STD-2 (adversarial review 23 Nov 2026): the pharmacy's VAT registration.
-- vat_registered: registered for VAT with TRA. A pharmacy that is not charges no VAT on any sale
--   and books no input VAT. Pharmacies that exist today keep charging the VAT their products carry
--   (true for every existing row, as before this column); one that is not registered switches it
--   off in its settings. New pharmacies start registered too (TenantSettings.defaults).
-- tin: the Taxpayer Identification Number, 9 digits in Tanzania (TRA; verify), kept as digits.
-- vrn: the VAT Registration Number, checked loosely (8 to 15 letters, digits and dashes; verify).
-- Columns with constant defaults: existing rows take them, the table is not rewritten and nothing
-- is updated. The standard rate (default_tax_rate_percent) is set from the products' rates by
-- catalogue V14, which runs after this schema.
alter table tenancy.settings
  add column vat_registered boolean not null default true,
  add column tin text check (tin is null or tin ~ '^[0-9]{9}$'),
  add column vrn text check (vrn is null or vrn ~ '^[0-9][0-9A-Z-]{7,14}$');
