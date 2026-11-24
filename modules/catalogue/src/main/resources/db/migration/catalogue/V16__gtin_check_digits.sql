-- STD-18: a product's barcode (gtin) is now checked as a GS1 GTIN: 8, 12, 13 or 14 digits ending in
-- the right check digit (422 invalid_gtin). Products saved before the check may hold one that fails
-- it. This migration only REPORTS them: it changes no row. They stay readable and editable (a
-- product keeps the GTIN it has), and the pharmacy corrects them from the product form.
--
-- The report is a WARNING per pharmacy in the migration log (Flyway logs it) and a NOTICE with the
-- total. To list them later, as the owner role:
--   select * from catalogue.invalid_gtins();
-- Reads every pharmacy: the products table forces row-level security even on the owner, so each
-- pharmacy is read with its own pharmacy.tenant_uid.

-- The GS1 check: from the right, the digits are weighted 3, 1, 3, 1…; the check digit brings their
-- sum to a multiple of 10.
create or replace function catalogue.gtin_valid(code text) returns boolean
  language sql immutable strict
as $$
  select case
           when code !~ '^([0-9]{8}|[0-9]{12,14})$' then false
           else (10 - (select sum(substr(code, length(code) - i, 1)::int
                                  * case when i % 2 = 1 then 3 else 1 end)
                         from generate_series(1, length(code) - 1) as i) % 10) % 10
                = substr(code, length(code), 1)::int
         end
$$;

-- Every pharmacy's products whose GTIN fails the check (for the owner role; changes nothing).
create or replace function catalogue.invalid_gtins()
  returns table (tenant_uid uuid, product_uid uuid, sku text, name text, gtin text)
  language plpgsql
as $$
declare
  t record;
begin
  for t in select uid from tenancy.tenants order by code loop
    perform set_config('pharmacy.tenant_uid', t.uid::text, true);
    return query
      select p.tenant_uid, p.uid, p.sku, p.name, p.gtin
        from catalogue.products p
       where p.gtin is not null and not catalogue.gtin_valid(p.gtin)
       order by p.sku;
  end loop;
  perform set_config('pharmacy.tenant_uid', '', true);
end;
$$;
revoke all on function catalogue.invalid_gtins() from public;

do $$
declare
  r record;
  total int := 0;
begin
  for r in
    select i.tenant_uid, count(*) as n, string_agg(i.sku || '=' || i.gtin, ', ' order by i.sku) as list
      from catalogue.invalid_gtins() i
     group by i.tenant_uid
  loop
    total := total + r.n;
    raise warning 'catalogue: % product(s) of pharmacy % have a GTIN that fails the GS1 check digit: %',
      r.n, r.tenant_uid, r.list;
  end loop;
  raise notice 'catalogue: % product GTIN(s) fail the GS1 check digit (reported, not changed)', total;
end
$$;
