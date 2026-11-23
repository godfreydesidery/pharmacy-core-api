-- What each supplier payment settled of each goods receipt it names (adversarial review MON-18): a
-- receipt's open amount is its total less what payments have already settled of it, in the
-- receipt's currency, and a payment may not settle more than is open (422 overpayment). Any part
-- of a payment above the receipts it names is not allowed; a payment naming no receipt is on
-- account and has no allocation.
create table finance.supplier_payment_allocations (
  id                  bigint generated always as identity primary key,
  uid                 uuid not null unique,
  tenant_uid          uuid not null,
  supplier_payment_id bigint not null references finance.supplier_payments (id),
  goods_receipt_uid   uuid not null,
  currency            text not null check (currency ~ '^[A-Z]{3}$'),
  amount              numeric(14,2) not null check (amount > 0),
  created_at          timestamptz not null,
  created_by          text not null,
  created_by_uid      uuid,
  updated_at          timestamptz,
  updated_by          text,
  updated_by_uid      uuid,
  version             bigint not null default 0
);
create index supplier_payment_allocations_by_receipt
  on finance.supplier_payment_allocations (tenant_uid, goods_receipt_uid);
create index supplier_payment_allocations_by_payment
  on finance.supplier_payment_allocations (supplier_payment_id);
alter table finance.supplier_payment_allocations enable row level security;
alter table finance.supplier_payment_allocations force row level security;
create policy tenant_isolation on finance.supplier_payment_allocations
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on finance.supplier_payment_allocations to ${application_role};

-- Payments made before: each settled the receipts it named in the order named, each up to what was
-- still open of it, oldest payment first (the same rule the application now applies). Every
-- pharmacy in turn, under its own row-level security; only rows are inserted, nothing changed.
do $$
declare
  t         uuid;
  p         record;
  r         record;
  remaining numeric(14,2);
  settled   numeric(14,2);
  portion   numeric(14,2);
begin
  for t in select uid from tenancy.tenants loop
    perform set_config('pharmacy.tenant_uid', t::text, true);
    for p in
      select id, amount, currency, goods_receipt_uids, created_at, created_by, created_by_uid
        from finance.supplier_payments
       where goods_receipt_uids is not null and goods_receipt_uids <> ''
       order by id
    loop
      remaining := p.amount;
      for r in
        select g.uid, g.total
          from unnest(string_to_array(p.goods_receipt_uids, ',')) with ordinality as named(uid, n)
          join purchasing.goods_receipts g on g.uid = named.uid::uuid and g.currency = p.currency
         order by named.n
      loop
        exit when remaining <= 0;
        select coalesce(sum(amount), 0) into settled
          from finance.supplier_payment_allocations where goods_receipt_uid = r.uid;
        portion := least(remaining, r.total - settled);
        if portion > 0 then
          insert into finance.supplier_payment_allocations
            (uid, tenant_uid, supplier_payment_id, goods_receipt_uid, currency, amount,
             created_at, created_by, created_by_uid)
          values (gen_random_uuid(), t, p.id, r.uid, p.currency, portion,
                  p.created_at, p.created_by, p.created_by_uid);
          remaining := remaining - portion;
        end if;
      end loop;
    end loop;
  end loop;
  perform set_config('pharmacy.tenant_uid', '', true);
end
$$;
