-- tenancy: the tenant registry (pharmacy businesses), their branches and settings.

grant usage on schema tenancy to ${application_role};

-- The tenant registry itself. Its uid is the tenant's identity, not a tenant key on someone else's
-- row, which is why this table alone carries no isolation policy.
create table tenancy.tenants (
  id            bigint generated always as identity primary key,
  uid           uuid not null unique,
  code          text not null unique,
  name          text not null,
  legal_name    text,
  country       text not null,
  currency      text not null,
  timezone      text not null,
  status        text not null check (status in ('ACTIVE', 'SUSPENDED')),
  status_reason text,
  created_at    timestamptz not null,
  created_by    text not null,
  updated_at    timestamptz,
  updated_by    text,
  version       bigint not null default 0
);
grant select, insert, update on tenancy.tenants to ${application_role};

create table tenancy.branches (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null references tenancy.tenants (uid),
  code           text not null,
  name           text not null,
  address        text,
  phone          text,
  licence_number text,
  head_office    boolean not null default false,
  active         boolean not null default true,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0,
  unique (tenant_uid, code)
);
alter table tenancy.branches enable row level security;
alter table tenancy.branches force row level security;
create policy tenant_isolation on tenancy.branches
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on tenancy.branches to ${application_role};

create table tenancy.settings (
  id                       bigint generated always as identity primary key,
  uid                      uuid not null unique,
  tenant_uid               uuid not null unique references tenancy.tenants (uid),
  default_tax_rate_percent numeric(5, 2) not null,
  invoice_prefix           text not null,
  receipt_footer           text,
  max_discount_percent     numeric(5, 2) not null,
  expiry_alert_days        integer not null,
  low_stock_alerts         boolean not null,
  created_at               timestamptz not null,
  created_by               text not null,
  updated_at               timestamptz,
  updated_by               text,
  version                  bigint not null default 0
);
alter table tenancy.settings enable row level security;
alter table tenancy.settings force row level security;
create policy tenant_isolation on tenancy.settings
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on tenancy.settings to ${application_role};

-- The number of a tenant's branches, for the platform's tenant list, without opening the branches
-- table to cross-tenant reads: it names the tenant for this one count, then restores the setting.
create function tenancy.branch_count(p_tenant uuid) returns integer
  language plpgsql volatile security definer
  set search_path = pg_catalog, pg_temp
as $$
declare
  previous text := current_setting('pharmacy.tenant_uid', true);
  result   integer;
begin
  perform set_config('pharmacy.tenant_uid', p_tenant::text, true);
  select count(*)::integer into result from tenancy.branches where tenant_uid = p_tenant;
  perform set_config('pharmacy.tenant_uid', coalesce(previous, ''), true);
  return result;
end
$$;
revoke all on function tenancy.branch_count(uuid) from public;
grant execute on function tenancy.branch_count(uuid) to ${application_role};

-- The number of branches of every tenant, for the platform overview.
create function tenancy.branch_total() returns bigint
  language sql volatile security definer
  set search_path = pg_catalog, pg_temp
as $$ select coalesce(sum(tenancy.branch_count(t.uid)), 0)::bigint from tenancy.tenants t $$;
revoke all on function tenancy.branch_total() from public;
grant execute on function tenancy.branch_total() to ${application_role};
