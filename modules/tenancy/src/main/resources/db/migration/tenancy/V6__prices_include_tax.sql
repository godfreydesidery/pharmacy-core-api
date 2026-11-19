-- Whether the pharmacy's selling prices already include VAT (the tax inside the price) or exclude it
-- (the tax added on top at the till). Pharmacies that exist keep adding it on top, as they have been;
-- a new pharmacy starts with prices that include it (TenantSettings.defaults()).

alter table tenancy.settings add column prices_include_tax boolean not null default false;
