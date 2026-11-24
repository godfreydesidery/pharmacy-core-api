-- STD-2 (adversarial review 23 Nov 2026): every product gets its VAT category.
--   STANDARD    sold at the pharmacy's standard rate (tenancy.settings.default_tax_rate_percent)
--   ZERO_RATED  sold at 0%, its input VAT recoverable (mainly exports in Tanzania; verify)
--   EXEMPT      outside VAT (most medicines and medical supplies in Tanzania; verify)
--
-- Existing products keep what they charge today:
--   a rate above 0  -> STANDARD, and its rate is kept as it is;
--   a rate of 0     -> EXEMPT. The choice: the Tanzanian VAT Act 2014 lists medicines and medical
--                      supplies among the exempt supplies, not the zero-rated ones, and a
--                      pharmacy's 0% products are those; a product that is really zero-rated is
--                      changed to ZERO_RATED by the pharmacy (it charges 0% either way, so no sale
--                      changes; only the VAT report's split and input VAT recovery differ).
-- The column has a constant default, so adding it does not rewrite the table; only the taxed
-- products are updated.
--
-- The pharmacy's standard rate is then set from its products where it was left at 0 (new
-- pharmacies used to start at 0, the rate being kept per product): the rate most of its taxed
-- products carry, else 18 (Tanzania's standard rate; verify). Its STANDARD products are sold at
-- that rate from now on (and at a new one when the pharmacy changes it).
--
-- Migrations run as the tables' owner, whom forced row-level security would hold to no tenant: it
-- is lifted while the rows are filled, and restored.

alter table catalogue.products
  add column tax_category text not null default 'EXEMPT'
    check (tax_category in ('STANDARD', 'ZERO_RATED', 'EXEMPT'));

alter table catalogue.products no force row level security;
update catalogue.products set tax_category = 'STANDARD' where tax_rate_percent > 0;
alter table catalogue.products force row level security;

alter table tenancy.settings no force row level security;
alter table catalogue.products no force row level security;
-- A pharmacy that charges no VAT today (a standard rate of 0 and no taxed product) is recorded as
-- not VAT-registered, so nothing it sells starts carrying VAT; its admin turns registration on in
-- Settings. A pharmacy with taxed products stays registered (tenancy V15's default).
update tenancy.settings s
   set vat_registered = false
 where s.default_tax_rate_percent = 0
   and not exists (select 1 from catalogue.products p
                    where p.tenant_uid = s.tenant_uid and p.tax_rate_percent > 0);
update tenancy.settings s
   set default_tax_rate_percent = coalesce(
         (select mode() within group (order by p.tax_rate_percent)
            from catalogue.products p
           where p.tenant_uid = s.tenant_uid and p.tax_rate_percent > 0),
         18)
 where s.default_tax_rate_percent = 0;
alter table catalogue.products force row level security;
alter table tenancy.settings force row level security;
