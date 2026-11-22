-- Every product has a code, unique in its pharmacy (the column stays named sku). Products without
-- one get the pharmacy's next P00001-style codes, after any P-number already in use; the
-- pharmacy's product-code counter is set past them, so new products carry on from there; and
-- inventory's copy of each product takes its code. Then the code is required.
--
-- Migrations run as the tables' owner, whom forced row-level security would hold to no tenant: it
-- is lifted while the rows are filled, and restored.

alter table catalogue.products no force row level security;
alter table platform.document_sequences no force row level security;

with taken as (
  select tenant_uid,
         max(substring(sku from '^[Pp]([0-9]+)$')::bigint) as top
    from catalogue.products
   where sku ~ '^[Pp][0-9]+$'
   group by tenant_uid),
numbered as (
  select p.id,
         p.tenant_uid,
         row_number() over (partition by p.tenant_uid order by p.created_at, p.id) as n
    from catalogue.products p
   where p.sku is null or btrim(p.sku) = '')
update catalogue.products p
   set sku = 'P' || lpad((coalesce(t.top, 0) + x.n)::text, 5, '0')
  from numbered x
  left join taken t on t.tenant_uid = x.tenant_uid
 where p.id = x.id;

insert into platform.document_sequences (tenant_uid, sequence_key, last_value)
select tenant_uid, 'product-code', max(substring(sku from '^[Pp]([0-9]+)$')::bigint)
  from catalogue.products
 where sku ~ '^[Pp][0-9]+$'
 group by tenant_uid
on conflict (tenant_uid, sequence_key)
  do update set last_value = greatest(platform.document_sequences.last_value, excluded.last_value);

alter table platform.document_sequences force row level security;
alter table catalogue.products force row level security;

-- Inventory's copy of each product, when inventory is already installed (it migrates after this).
do $$
begin
  if to_regclass('inventory.products') is not null then
    execute 'alter table inventory.products no force row level security';
    execute 'update inventory.products ip set sku = p.sku from catalogue.products p'
         || ' where ip.product_uid = p.uid and ip.sku is distinct from p.sku';
    execute 'alter table inventory.products force row level security';
  end if;
end
$$;

alter table catalogue.products alter column sku set not null;
